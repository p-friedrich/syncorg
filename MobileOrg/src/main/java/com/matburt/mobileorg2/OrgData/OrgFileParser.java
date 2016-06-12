package com.matburt.mobileorg2.OrgData;

import android.content.ContentResolver;
import android.content.Context;
import android.text.TextUtils;
import android.util.Log;


import com.matburt.mobileorg2.util.PreferenceUtils;

import java.io.BufferedReader;
import java.io.IOException;
import java.security.Timestamp;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Stack;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class OrgFileParser {
	public static final String BLOCK_SEPARATOR_PREFIX = "#HEAD#";

	private ContentResolver resolver;
    private OrgDatabase db;
 
    private ParseStack parseStack;
	private StringBuilder payload;
	private OrgFile orgFile;
	private OrgNodeParser orgNodeParser;
	private HashSet<String> excludedTags;
    private HashMap<Integer, Integer> position;
	private HashMap<String, Integer> timestamps;

	public OrgFileParser(OrgDatabase db, ContentResolver resolver) {
		this.db = db;
		this.resolver = resolver;
	}

	private void init(OrgFile orgFile) {
		orgFile.removeFile(resolver);
		orgFile.addFile(resolver);
		this.orgFile = orgFile;

		this.parseStack = new ParseStack();
		this.parseStack.add(0, orgFile.nodeId, "");
		
		this.payload = new StringBuilder();
		
		this.orgNodeParser = new OrgNodeParser(
				OrgProviderUtils.getTodos(resolver));

        this.position = new HashMap<>();
	}
	
	public void parse(OrgFile orgFile, BufferedReader breader, Context context) {
		this.excludedTags = PreferenceUtils.getExcludedTags();
		
		parse(orgFile, breader);
	}
	
	public void parse(OrgFile orgFile, BufferedReader breader) {
		init(orgFile);
		db.beginTransaction();
		timestamps = new HashMap<>();

		try {
			String currentLine;
			while ((currentLine = breader.readLine()) != null) parseLine(currentLine);

			// Add payload to the final node
			db.fastInsertNodePayload(parseStack.getCurrentNodeId(), this.payload.toString(), timestamps);

		} catch (IOException e) {}
		
		db.endTransaction();

	}


	private void parseLine(String line) {
		if (TextUtils.isEmpty(line))
			return;

		int numstars = numberOfStars(line);
		if (numstars > 0) {
			timestamps = new HashMap<>();
			db.fastInsertNodePayload(parseStack.getCurrentNodeId(), this.payload.toString(), timestamps);
			this.payload = new StringBuilder();
			parseHeading(line, numstars);
		} else {
			timestamps = parseTimestamps(line);
			payload.append(line).append("\n");
		}
	}
	
	private void parseHeading(String thisLine, int numstars) {
        int currentLevel = parseStack.getCurrentLevel();

        if(position.get(numstars-1) == null) position.put(numstars-1, 0);
        if (numstars <= currentLevel) {
            int value = position.get(numstars-1);
            position.put(numstars-1, value+1);
		} else {
            position.put(numstars-1, 0);
        }

		while (numstars <= parseStack.getCurrentLevel()){
            parseStack.pop();
        }

		OrgNode node = this.orgNodeParser.parseLine(thisLine, numstars);
		node.tags_inherited = parseStack.getCurrentTags();
		node.fileId = orgFile.id;
		node.parentId = parseStack.getCurrentNodeId();
        node.position = position.get(numstars-1);

        long newId = db.fastInsertNode(node);
		parseStack.add(numstars, newId, node.tags);
    }

	private static final Pattern starPattern = Pattern.compile("^(\\**)\\s");
	private static int numberOfStars(String thisLine) {
		Matcher matcher = starPattern.matcher(thisLine);
		if(matcher.find()) {
			return matcher.end(1) - matcher.start(1);
		} else
			return 0;
	}


	/**
	 * Parse the line for any timestamps
	 * @param line
	 * @return A HashMap<String,int> where first key is timestamp type and second is value.
	 * Return null if no timestamp found.
	 */
	private HashMap<String, Integer> parseTimestamps(String line){
		HashMap<String, Integer> result = new HashMap<>();

		HashMap<String, OrgNodeTimeDate.TYPE> timestamps = new HashMap<>();
		timestamps.put(OrgDatabase.Scheduled, OrgNodeTimeDate.TYPE.Scheduled);
		timestamps.put(OrgDatabase.Deadline, OrgNodeTimeDate.TYPE.Deadline);


		for(Map.Entry<String, OrgNodeTimeDate.TYPE> entry: timestamps.entrySet()){
			Matcher matcher = OrgNodePayload.getTimestampMatcher(entry.getValue())
					.matcher(payload);

            String str = OrgNodePayload.getDateFromTimestampMatcher(matcher);
            result.put(entry.getKey(), 0);
        }

		return result;
	}
	
    
	private class ParseStack {
        private class Item {
            int level;
            long nodeId;
            String tag;

            public Item(Integer level, Long nodeId, String tags){
                this.level = level;
                this.nodeId = nodeId;
                this.tag = tags;
            }
        }

		private Stack<Item> stack;

		public ParseStack() {
			this.stack = new Stack<Item>();
		}
		
		public void add(int level, long nodeId, String tags) {
			stack.push(new Item(level, nodeId, stripTags(tags)));
		}
		
		private String stripTags(String tags) {
			if (excludedTags == null || TextUtils.isEmpty(tags))
				return tags;
			
			StringBuilder result = new StringBuilder();
			for (String tag: tags.split(":")) {
				if (!excludedTags.contains(tag)) {
					result.append(tag);
					result.append(":");
				}
			}
			
			if(!TextUtils.isEmpty(result))
				result.deleteCharAt(result.lastIndexOf(":"));
			
			return result.toString();
		}
		
		public void pop() {
			this.stack.pop();
		}
		
		public int getCurrentLevel() {
			return stack.peek().level;
		}
		
		public long getCurrentNodeId() {
			return stack.peek().nodeId;
		}
		
		public String getCurrentTags() {
			return stack.peek().tag;
		}

	}

	/**
	 * Parses the checksum file.
	 * @return HashMap with Filename->checksum
	 */
	public static HashMap<String, String> getChecksums(String filecontents) {
		HashMap<String, String> checksums = new HashMap<String, String>();
		for (String line : filecontents.split("[\\n\\r]+")) {
			if (TextUtils.isEmpty(line))
				continue;
			String[] chksTuple = line.split("  ", 2);
			if(chksTuple.length == 2)
				checksums.put(chksTuple[1], chksTuple[0]);
		}
		return checksums;
	}
	
	private static final String fileMatchPattern = "\\[file:(.*?)\\]\\[(.*?)\\]\\]";
	/**
	 * Parses the file list from index file.
	 * @return HashMap with Filename->Filename Alias
	 */
	public static HashMap<String, String> getFilesFromIndex(String filecontents) {
		Pattern indexOrgFilePattern = Pattern.compile(fileMatchPattern);
		Matcher indexOrgFileMatcher = indexOrgFilePattern.matcher(filecontents);
		HashMap<String, String> allOrgFiles = new HashMap<>();
				
		while (indexOrgFileMatcher.find()) {
			allOrgFiles.put(indexOrgFileMatcher.group(1), indexOrgFileMatcher.group(2));
		}

		return allOrgFiles;
	}
	
	
	private static final Pattern getTodos = Pattern
			.compile("#\\+TODO:([^\\|]+)(\\| (.*))*");

	public static ArrayList<HashMap<String, Boolean>> getTodosFromIndex(String filecontents) {
		String[] lines = filecontents.split("\\n");
		ArrayList<HashMap<String, Boolean>> todoList = new ArrayList<>();
		for(String line: lines){
			Matcher m = getTodos.matcher(line);
			while (m.find()) {
				String lastTodo = "";
				HashMap<String, Boolean> holding = new HashMap<>();
				Boolean isDone = false;
				for (int idx = 1; idx <= m.groupCount(); idx++) {
					if (m.group(idx) != null && m.group(idx).trim().length() > 0) {
						if (m.group(idx).contains("|")) {
							isDone = true;
							continue;
						}
						String[] grouping = m.group(idx).trim().split("\\s+");
						for (String group : grouping) {
							lastTodo = group.trim();
							holding.put(group.trim(), isDone);
						}
					}
				}
				if (!isDone) {
					holding.put(lastTodo, true);
				}
				todoList.add(holding);
			}
		}

		return todoList;
	}
	
	private static final Pattern getPriorities = Pattern
			.compile("#\\+ALLPRIORITIES:([^\\n]+)");
	public static ArrayList<String> getPrioritiesFromIndex(String filecontents) {
		Matcher t = getPriorities.matcher(filecontents);

		ArrayList<String> priorities = new ArrayList<String>();

		if (t.find()) {
			if (t.group(1) != null && t.group(1).trim().length() > 0) {
				String[] grouping = t.group(1).trim().split("\\s+");
				for (String group : grouping)
					priorities.add(group.trim());
			}
		}
		return priorities;
	}
	
	
	private static final Pattern getTags = Pattern.compile("#\\+TAGS:([^\\n]+)");
	public static ArrayList<String> getTagsFromIndex(String filecontents) {
		Matcher matcher = getTags.matcher(filecontents);
		ArrayList<String> tagList = new ArrayList<String>();
		
		if(matcher.find()) {
			String tags = matcher.group(1).trim().replaceAll("[\\{\\}]", "");
			String[] split = tags.split("\\s+");
			
			if(split.length == 1 && split[0].equals(""))
				return tagList;
			
			for(String tag: split)
				tagList.add(tag);
		}
		
		return tagList;
	}
}
