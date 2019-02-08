package cafeGraphSHARK;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.mongodb.morphia.Datastore;
import org.mongodb.morphia.query.Query;
import org.slf4j.LoggerFactory;

import ch.qos.logback.classic.Logger;
import common.HunkSignatureHandler;
import common.MongoAdapter;
import common.cafe.CafeGraphParameter;
import de.ugoe.cs.smartshark.model.CFAState;
import de.ugoe.cs.smartshark.model.CodeEntityState;
import de.ugoe.cs.smartshark.model.Commit;
import de.ugoe.cs.smartshark.model.File;
import de.ugoe.cs.smartshark.model.FileAction;
import de.ugoe.cs.smartshark.model.Hunk;
import de.ugoe.cs.smartshark.model.HunkBlameLine;

/**
 * @author Philip Makedonski
 */

public class CafeGraphApp {
	protected Datastore targetstore;
	protected MongoAdapter adapter;
	private HunkSignatureHandler hsh = new HunkSignatureHandler();
	protected static Logger logger = (Logger) LoggerFactory.getLogger(CafeGraphApp.class.getSimpleName());

	public static void main(String[] args) {
		CafeGraphParameter.getInstance().init(args);
		
		CafeGraphApp app = new CafeGraphApp();
		
		if (CafeGraphParameter.getInstance().getCommit() == null) {
			app.processRepository();
		} else {
			app.processCommit();
		}
	}
	
	public CafeGraphApp() {
		init();
	}

	void init() {
		adapter = new MongoAdapter(CafeGraphParameter.getInstance());
		adapter.setPluginName("cafeGraphSHARK");
		adapter.setRecordProgress(CafeGraphParameter.getInstance().isRecordProgress());
		targetstore = adapter.getTargetstore();
		adapter.setVcs(CafeGraphParameter.getInstance().getUrl());
		if (adapter.getVcs()==null) {
			logger.error("No VCS information found for "+CafeGraphParameter.getInstance().getUrl());
			System.exit(1);
		}
	}

	
	public void processRepository() {
		List<Commit> commits = adapter.getCommits();
		int i = 0;
		int size = commits.size();
		for (Commit commit : commits) {
			i++;
			logger.info("Processing: "+i+"/"+size);
			processCommit(commit);
//			if (i == 20) break;
		}
	}
	
	public void processCommit() {
		processCommit(CafeGraphParameter.getInstance().getCommit());
	}

	public void processCommit(String hash) {
        processCommit(adapter.getCommit(hash));
	}
	
	public void processCommit(Commit commit) {
        logger.info(commit.getRevisionHash().substring(0, 8) + " " + commit.getAuthorDate());

        if (commit.getParents() == null) {
        	commit.setParents(new ArrayList<>());
        }
        
        List<FileAction> actions = adapter.getActions(commit);

        //deal with merges
        //  -> skip altogether?
        //     hunks and actions are effectively duplicated for merged commits

        if (CafeGraphParameter.getInstance().isProcessMerges() || commit.getParents().size()<2) {

	        for (FileAction a : actions) {
	        	//check for special cases: mode A, R, D, etc.?
	        	//TODO: if rename action -> use old file name?
	        	if (a.getMode().equals("A")) {
	        		//skip newly added
	        		//continue;
	        	}
	            
	            List<Hunk> hunks = adapter.getHunks(a);
	            
	            //-> double check off-by-one (0-based or 1-based)
	            //  -> [old|new]StartLine is 0-based when [old|new]Lines = 0
	            //     - hunk removed or added
	            //     - only affects the corresponding side [old|new]
	            //  -> [old|new]StartLine is 1-based when [old|new]Lines > 0
	            //     - hunk modified
	            //-> make sure it is consistent for old and new
	            //  -> interpolate as necessary
	
	            //hunk interpolation (not saved)
	            adapter.interpolateHunks(hunks);
	            
	            processProjectLevel(a, hunks);
	            processFileLevel(a, hunks);
	            processLogicalLevel(a, hunks);
	        }
        }

    	//update cached causes and flush
    	adapter.flushCFACache();
    	
        logger.info("Analyzed commit: " + commit.getRevisionHash());
	}

	private void processProjectLevel(FileAction a, List<Hunk> hunks) {
        CFAState pState = adapter.getCFAStateForEntity(a.getCommitId());
        if (pState == null) {
        	pState = new CFAState();
        	pState.setType("project");
        	pState.setEntityId(a.getCommitId());
        	adapter.saveCFAState(pState);
        }

        for (Hunk h : hunks) {
			for (HunkBlameLine hbl : adapter.getHunkBlameLines(h)) {
				CFAState pCause = adapter.getCFAStateForEntity(hbl.getBlamedCommitId());
				pState.getFixesIds().add(pCause.getId());
				pCause.getCausesIds().add(pState.getId());
			}
        }
	}

	private void processFileLevel(FileAction a, List<Hunk> hunks) {
        CFAState fState = adapter.getCFAStateForEntity(a.getId());
        if (fState == null) {
        	CFAState pState = adapter.getCFAStateForEntity(a.getCommitId());
        	fState = new CFAState();
            fState.setType("file");
            fState.setEntityId(a.getId());
            fState.setParentId(pState.getId());
            adapter.saveCFAState(fState);
            pState.getChildrenIds().add(fState.getId());
        }

        for (Hunk h : hunks) {
			for (HunkBlameLine hbl : adapter.getHunkBlameLines(h)) {
	    		//-> relies on hunk_blame_line with store source line
	    		//TODO: consider implementing option based on file actions only
	    		File f = adapter.getFile(hbl.getSourcePath());
				FileAction cAction = adapter.getAction(hbl.getBlamedCommitId(), f.getId());
				CFAState fCause = adapter.getCFAStateForEntity(cAction.getId());
				fState.getFixesIds().add(fCause.getId());
				fCause.getCausesIds().add(fState.getId());
	    	}
        }
	}
	
	private void processLogicalLevel(FileAction a, List<Hunk> hunks) {
		//NOTE: it goes across method and file boundaries
		//      -> causes may be in a different artifact state 

		List<String> parentHashes = adapter.getCommit(a.getCommitId()).getParents();
		if (parentHashes.size()>1) {
			//TODO: merges?
			return;
		}

		File f = adapter.getFile(a.getFileId());
		logger.info("  "
				+f.getPath());

//		Commit parent = getCommit(parentHashes.get(0));
//		File pf = f;
//		if (a.getOldFileId()!=null) {
//			pf = datastore.get(File.class, a.getOldFileId());
//		}

		//get candidate code entity states for action file

		LinkedHashMap<Integer, Hunk> linesPost = new LinkedHashMap<>();
		for (Hunk h : hunks) {
			for (int line = h.getNewStart(); line < h.getNewStart()+h.getNewLines(); line++) {
				linesPost.put(line, h);
			}
		}

		List<CodeEntityState> methodStates = adapter.getCodeEntityStates(a.getCommitId(), a.getFileId(), "method");
		
		for (CodeEntityState mState : methodStates ) {
			// -> filter hit states
			//   -> use spatial shark instead?
			// -> for the graph only the lines post are really relevant
			//    -> at first
			//    -> still need to consider the name of the previous state
			//    -> and also when code was moved between artifacts
			//       e.g. main to extracted method.. 
			//       -> collect information and filter if necessary
			// -> for decent analysis also the lines pre are needed 
			//    as that is the previous state
			List<Integer> linesPostHits = linesPost.keySet().stream().filter(e -> 
					e>=mState.getStartLine() &&
					e<=mState.getEndLine()).collect(Collectors.toList());
			
			logger.info("    "
				+mState.getStartLine()
				+"-"+mState.getEndLine()
				+" "+linesPostHits
				+" "+mState.getLongName());
			
			//note that blame lines are based on the pre-hits
			// -> perhaps a intra-hunk blame would provide more accurate data
			Set<Hunk> hitHunks = linesPostHits.stream()
				.map(e->linesPost.get(e))
				.collect(Collectors.toSet());
			processLogicalStateHunks(mState, a, hitHunks);
		}
	}

	private void processLogicalStateHunks(CodeEntityState s, FileAction a, Set<Hunk> hitHunks) {
		if (hitHunks.isEmpty()) {
			return;
		}
		
        CFAState lState = adapter.getCFAStateForEntity(s.getId());
        if (lState == null) {
        	CFAState fState = adapter.getCFAStateForEntity(a.getId());
        	lState = new CFAState();
            lState.setType("method");
            lState.setEntityId(s.getId());
            lState.setParentId(fState.getId());
            adapter.saveCFAState(lState);
            fState.getChildrenIds().add(lState.getId());
        }
        
		for (Hunk h : hitHunks) {
			LinkedHashMap<Integer,Integer> hunkLineMap = hsh.getHunkLineMap(h);
			for (HunkBlameLine hbl : adapter.getHunkBlameLines(h)) {
				List<CodeEntityState> cms = getCausingStates(hbl, s, h, hunkLineMap);
				//compare db against local version -> identical outcome
				//TODO: benchmark
				
				//TODO: only go through states that are within hbl
	            for (CodeEntityState cState : cms) {
	            	logger.info("    -> "
	            			+cState.getStartLine()
	            			+"-"+cState.getEndLine()
	            			+" ["+hbl.getSourceLine()+"-"+(hbl.getSourceLine()+hbl.getLineCount())+"]"
	            			+" "+cState.getLongName()
	            			+" @ "+adapter.getCommit(hbl.getBlamedCommitId()).getRevisionHash().substring(0,8));

	            	CFAState lCause = adapter.getCFAStateForEntity(cState.getId());
					//TODO: separate concern: why are hunkblames not compressed?!
					//TODO: investigate why lCause is null for 
					//safe at 67a4b6ec5dfd7f39f12de41789e840c9dc4c3b44
					//TODO: a more adequate handling of compresion is needed
					//      otherwise for hunks spanning big blocks it gets messy
					//      -> also refine selection of candidate states 
					//         (include b.start < s.start AND b.end > s.end)
					//         and use hitOffset + hitEndOffset for more precise location
					if (lCause == null) {
						Commit c = adapter.getCommit(a.getCommitId());
						Commit cc = adapter.getCommit(hbl.getBlamedCommitId());
						File f = adapter.getFile(a.getFileId());
						File cf = adapter.getFile(cState.getFileId());
						System.out.println(
								"Causing state not found:"
								+"\n"
								+" "+cState.getStartLine()+"-"+cState.getEndLine()
								+" "+cState.getLongName()
								+"\n in "+cf.getPath()
								+" @ "+cc.getRevisionHash().substring(0,8)
								+"\nfor "
								+"\n"
								+" "+s.getStartLine()+"-"+s.getEndLine()
								+" "+s.getLongName()
								+"\n in "+f.getPath()
								+" @ "+c.getRevisionHash().substring(0,8)
								);
						
					}
					
	            	lState.getFixesIds().add(lCause.getId());
	            	lCause.getCausesIds().add(lState.getId());
	            	
	            }
			}
		}
	}

	private List<CodeEntityState> getCausingStatesLocal(HunkBlameLine hbl) {
		File f = adapter.getFile(hbl.getSourcePath());
		
		List<CodeEntityState> allCauseMethodStates = adapter.getCodeEntityStates(hbl.getBlamedCommitId(), f.getId(), "method");
		
		List<CodeEntityState> cms = allCauseMethodStates.stream().filter(e -> 
			(hbl.getSourceLine() <= e.getStartLine() &&
			hbl.getSourceLine()+hbl.getLineCount() >= e.getStartLine()) ||
			(hbl.getSourceLine() >= e.getStartLine() &&
			hbl.getSourceLine() <= e.getEndLine())).collect(Collectors.toList());
		
		return cms;
	}

	private List<CodeEntityState> getCausingStates(HunkBlameLine hbl, CodeEntityState s, Hunk h, LinkedHashMap<Integer,Integer> hunkLineMap) {
		List<CodeEntityState> causingStates = new ArrayList<>();

		File f = adapter.getFile(hbl.getSourcePath());

		//target state
		int tStart = s.getStartLine();
		int tEnd = s.getEndLine();
		int tStartOffset = 0;
		int tEndOffset = 0;
		if (h.getNewStart() > s.getStartLine()) {
			tStart = h.getNewStart();
		}
		if (h.getNewStart()+h.getNewLines() <= s.getEndLine()) {
			tEnd = h.getNewStart()+h.getNewLines()-1;
		}

//		System.out.println("  ::  ::  ::"+hbl.getHunkLine()+"-"+(hbl.getHunkLine()+hbl.getLineCount()));
//		System.out.println(""
//				+"  "+tStart +" [+"+tStartOffset+"]"
//				+" - "+tEnd +" [+"+tEndOffset+"]"
//				);
		tStart = hunkLineMap.get(tStart);
		tEnd = hunkLineMap.get(tEnd);
		tStartOffset = tStart-hbl.getHunkLine();
		tEndOffset = tEnd-hbl.getHunkLine();
		
//		System.out.println(""
//				+"  "+tStart +" [+"+tStartOffset+"]"
//				+" - "+tEnd +" [+"+tEndOffset+"]"
//				);
		
		if (tEndOffset < 0  
			|| tStartOffset+1 > hbl.getLineCount()) {
			return causingStates;
		}
		
		int sStart = hbl.getSourceLine()+tStartOffset;
		int sEnd = hbl.getSourceLine()+tEndOffset;

//		System.out.println(""
//				+"  "+sStart +" [+"+tStartOffset+"]"
//				+" - "+sEnd +" [+"+tEndOffset+"]"
//				);

		Query<CodeEntityState> cmsQuery = adapter.getDatastore().find(CodeEntityState.class)
			.field("commit_id").equal(hbl.getBlamedCommitId())
			.field("file_id").equal(f.getId())
			.field("ce_type").equal("method");
		
		cmsQuery.or(
			cmsQuery.and(
//				cmsQuery.criteria("start_line").greaterThanOrEq(hbl.getSourceLine()),
//				cmsQuery.criteria("start_line").lessThanOrEq(hbl.getSourceLine()+hbl.getLineCount()-1)
				cmsQuery.criteria("start_line").lessThanOrEq(sStart),
				cmsQuery.criteria("end_line").greaterThanOrEq(sEnd)
//			),
//			cmsQuery.and(
//				cmsQuery.criteria("start_line").lessThanOrEq(hbl.getSourceLine()),
//				cmsQuery.criteria("end_line").greaterThanOrEq(hbl.getSourceLine())
//				cmsQuery.criteria("start_line").lessThanOrEq(hbl.getSourceLine()),
//				cmsQuery.criteria("end_line").greaterThanOrEq(hbl.getSourceLine())
			)
		);
		cmsQuery.order("start_line");
		
		return cmsQuery.asList();
	}
}
