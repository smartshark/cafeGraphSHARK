package cafeGraphSHARK;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import cafeGraphSHARK.DiffMatchPatch.Diff;
import de.ugoe.cs.smartshark.model.Commit;
import de.ugoe.cs.smartshark.model.Hunk;


public class HunkSignatureHandler {
	private DiffMatchPatch dmp = new DiffMatchPatch();

	public LinkedHashMap<Integer, Integer> getHunkLineMap(Hunk hunk) {
		//reverse map (new to old line)
		LinkedHashMap<Integer, Integer> lineMap = new LinkedHashMap<>();

		List<String> signature = getHunkSignature(hunk);
		int ol = hunk.getOldStart();
		int nl = hunk.getNewStart();
		lineMap.put(nl, ol);
		for (String s : signature) {
			switch (s) {
			case "A":
				nl++;
				break;
			case "R":
				ol++;
				break;
			case "M":
				nl++;
				ol++;
				break;
			default:
				break;
			}
			lineMap.put(nl, ol);
		}
		
		return lineMap;
	}
	
	//copied from VCSSharkHandler
	public List<String> getHunkSignature(Hunk hunk) {
		ArrayList<String> signature = new ArrayList<>();
		
		String patch = hunk.getContent();
		String[] patchLines = patch.split("\n");
		List<String> oldGappedLines = Arrays.stream(patchLines)
				.filter(l -> !l.startsWith("+"))
				.map(l -> l.replaceAll("^-", ""))
				.collect(Collectors.toList());
		List<String> newGappedLines = Arrays.stream(patchLines)
				.filter(l -> !l.startsWith("-"))
				.map(l -> l.replaceAll("^\\+", ""))
				.collect(Collectors.toList());
			
		String oldFragment = String.join("\n", oldGappedLines);
		String newFragment = String.join("\n", newGappedLines);
			
		LinkedList<Diff> diffs = dmp.diff_main(oldFragment, newFragment);
		ArrayList<String> lSignature = getLineBasedHunkSignature(diffs);
		signature.addAll(lSignature);
		return signature;
	}
	
	public ArrayList<String> getLineBasedHunkSignature(LinkedList<Diff> diffs) {
		//alternate approach evaluating line tags
		//not perfect but close
		ArrayList<String> lSignature = new ArrayList<>();
		List<String> lines = getAnnotatedPatchLines(diffs);
		String carryOver = "";
		String alias = "";
		for (String line : lines) {
				
			Pattern pattern = Pattern.compile("\\[((DELETE)|(INSERT)|(EQUAL))\\]");
			Matcher m = pattern.matcher(line);
			List<String> lineTags = new LinkedList<>();
			while (m.find()) {
				lineTags.add(m.group(1));
			}
			if (lineTags.isEmpty()) {
				if (line.matches("\\s+") || line.length() == 0) {
					alias = "M";
				} else if (carryOver.equals("")) {
					alias = lSignature.get(lSignature.size()-1);
				} else {
					alias = carryOver;
				}
			} else {
				if (lineTags.size()>1) {
					if (carryOver.equals("R")) {
						alias = carryOver;
					} else if (carryOver.equals("A")) {
						alias = carryOver;
					} else {
						alias = "M";
					}
				} else if (lineTags.size() == 1) {
					alias = getTagAlias(lineTags.get(0));
					if (line.indexOf(lineTags.get(0))==1) {
						if (alias.equals("A")) {
							if (carryOver.equals("R")) {
								alias = carryOver;
							}
						} else if (alias.equals("R")) {
						} else {
							if (carryOver.equals("R")) {
								alias = carryOver;
							} else if (carryOver.equals("A")) {
								alias = carryOver;
							} else {
								alias = "M";
							}
						}
					} else {
						if (carryOver.equals("R")) {
							alias = carryOver;
						} else if (carryOver.equals("A")) {
							alias = carryOver;
						} else {
							alias = "M";
						}
					}
				} else {
					if (carryOver.equals("A")) {
						alias = carryOver;
					} else if (carryOver.equals("R")) {
						alias = carryOver;
					} else {
						alias = "M";
					}
				}
				String lastLineTag = lineTags.get(lineTags.size()-1);
				if (line.indexOf(lastLineTag)==1 && lineTags.size()==1) {
					carryOver = "";
				} else {
					carryOver = getTagAlias(lastLineTag);
				}
			}
			lSignature.add(alias);
//			System.out.println(alias+"\t"+line);
		}
		return lSignature;
	}
	
	private String getTagAlias(String tag) {
		String alias = "";
		switch (tag) {
		case "INSERT":
			alias="A";
			break;
		case "DELETE":
			alias="R";
			break;
		case "EQUAL":
			alias="M";
			break;
		default:
			break;
		}
		return alias;
	}

	public List<String> getAnnotatedPatchLines(LinkedList<Diff> diffs) {
		String mText = "";
		boolean skipSpaces = false;
		for (Diff d : diffs) {
			if (skipSpaces && d.text.matches("\\s+") && !d.text.matches("\n+")) {
				mText+=d.text;
			} else {
				mText+="["+d.operation+"]"+d.text;
			}
		}
		List<String> lines = Arrays.asList(mText.split("\n"));
		return lines;
	}

}
