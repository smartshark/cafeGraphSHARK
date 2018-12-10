package cafeGraphSHARK;

import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.bson.types.ObjectId;
import org.mongodb.morphia.Datastore;
import org.mongodb.morphia.query.Query;
import org.slf4j.LoggerFactory;

import ch.qos.logback.classic.Logger;
import common.DatabaseHandler;
import common.cafe.CafeGraphConfigurationHandler;
import common.cafe.CafeGraphParameter;
import de.ugoe.cs.smartshark.model.CFAState;
import de.ugoe.cs.smartshark.model.CodeEntityState;
import de.ugoe.cs.smartshark.model.Commit;
import de.ugoe.cs.smartshark.model.File;
import de.ugoe.cs.smartshark.model.FileAction;
import de.ugoe.cs.smartshark.model.Hunk;
import de.ugoe.cs.smartshark.model.HunkBlameLine;
import de.ugoe.cs.smartshark.model.VCSSystem;

/**
 * @author Philip Makedonski
 */

public class CafeGraphApp {
	protected Datastore datastore;
	protected Datastore targetstore;
	private HashMap<String, Commit> commitCache = new HashMap<>();
	private HashMap<String, File> fileCache = new HashMap<>();
	private HashMap<ObjectId, Commit> commitIdCache = new HashMap<>();
	private HashMap<ObjectId, List<HunkBlameLine>> hblCache = new HashMap<>();
	private HashMap<ObjectId, CFAState> cfaCache = new HashMap<>();
	private HashMap<ObjectId, CFAState> cfaEntityCache = new HashMap<>();
	protected VCSSystem vcs;
	protected static Logger logger = (Logger) LoggerFactory.getLogger(CafeGraphApp.class.getSimpleName());

	public static void main(String[] args) {
		//load configuration -> override parameters
		if (args.length == 1) {
			args = CafeGraphConfigurationHandler.getInstance().loadConfiguration("properties/sample");
		}
		
		CafeGraphParameter.getInstance().init(args);
		CafeGraphConfigurationHandler.getInstance().setLogLevel(CafeGraphParameter.getInstance().getDebugLevel());
		
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
		//TODO: make optional or merge
//		targetstore = DatabaseHandler.createDatastore("localhost", 27017, "cfashark");
		datastore = DatabaseHandler.createDatastore(CafeGraphParameter.getInstance());
		targetstore = datastore;
		vcs = datastore.find(VCSSystem.class)
    		.field("url").equal(CafeGraphParameter.getInstance().getUrl()).get();
	}

	
	public void processRepository() {
		List<Commit> commits = datastore.find(Commit.class)
				.field("vcs_system_id").equal(vcs.getId()).asList();
		int i = 0;
		int size = commits.size();
		
		//try to avoid relying on temporal order 
		// -> currently needed
		// -> it might fail in some cases
    	Collections.sort(commits, new Comparator<Commit>() {
    		@Override
    		public int compare(Commit o1, Commit o2) {
    			return o1.getAuthorDate().compareTo(o2.getAuthorDate());
    		}
    	});
		
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
        processCommit(getCommit(hash));
	}
	
	public void processCommit(Commit commit) {
        logger.info(commit.getRevisionHash().substring(0, 8) + " " + commit.getAuthorDate());
        
        List<FileAction> actions = datastore.find(FileAction.class)
    		.field("commit_id").equal(commit.getId()).asList();

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
	            
	            List<Hunk> hunks = datastore.find(Hunk.class)
            		.field("file_action_id").equal(a.getId()).asList();
	            
	            //-> double check off-by-one (0-based or 1-based)
	            //  -> [old|new]StartLine is 0-based when [old|new]Lines = 0
	            //     - hunk removed or added
	            //     - only affects the corresponding side [old|new]
	            //  -> [old|new]StartLine is 1-based when [old|new]Lines > 0
	            //     - hunk modified
	            //-> make sure it is consistent for old and new
	            //  -> interpolate as necessary
	
	            //hunk interpolation (not saved)
	            interpolateHunks(hunks);
	            
	            processProjectLevel(a, hunks);
	            processFileLevel(a, hunks);
	            processLogicalLevel(a, hunks);
	        }
        }

    	//update cached causes and flush
    	for (CFAState s : cfaCache.values()) {
        	targetstore.save(s);
    	}
    	//clear or keep?
    	cfaCache.clear();
    	cfaEntityCache.clear();
    	
        logger.info("Analyzed commit: " + commit.getRevisionHash());
	}

	private void interpolateHunks(List<Hunk> hunks) {
		for (Hunk h : hunks) {
			if (h.getOldLines()==0) {
				h.setOldStart(h.getOldStart()+1);
			}
			if (h.getNewLines()==0) {
				h.setNewStart(h.getNewStart()+1);
			}
		}
	}
	
	private void processProjectLevel(FileAction a, List<Hunk> hunks) {
        CFAState pState = getCFAStateForEntity(a.getCommitId());
        if (pState == null) {
        	pState = new CFAState();
        	pState.setType("project");
        	pState.setEntityId(a.getCommitId());
            targetstore.save(pState);
            cfaCache.put(pState.getId(), pState);
            cfaEntityCache.put(pState.getEntityId(), pState);
        }

        for (Hunk h : hunks) {
			for (HunkBlameLine hbl : getHunkBlameLines(h)) {
				CFAState pCause = getCFAStateForEntity(hbl.getBlamedCommitId());
				pState.getFixesIds().add(pCause.getId());
				pCause.getCausesIds().add(pState.getId());
			}
        }
	}

	private void processFileLevel(FileAction a, List<Hunk> hunks) {
        CFAState fState = getCFAStateForEntity(a.getId());
        if (fState == null) {
        	CFAState pState = getCFAStateForEntity(a.getCommitId());
        	fState = new CFAState();
            fState.setType("file");
            fState.setEntityId(a.getId());
            fState.setParentId(pState.getId());
            targetstore.save(fState);
            cfaCache.put(fState.getId(), fState);
            cfaEntityCache.put(fState.getEntityId(), fState);
            pState.getChildrenIds().add(fState.getId());
        }

        for (Hunk h : hunks) {
			for (HunkBlameLine hbl : getHunkBlameLines(h)) {
	    		//-> relies on hunk_blame_line with store source line
	    		//TODO: consider implementing option based on file actions only
	    		File f = getFile(hbl.getSourcePath());
				FileAction cAction = datastore.find(FileAction.class)
					.field("commit_id").equal(hbl.getBlamedCommitId())
					.field("file_id").equal(f.getId()).get();
				CFAState fCause = getCFAStateForEntity(cAction.getId());
				fState.getFixesIds().add(fCause.getId());
				fCause.getCausesIds().add(fState.getId());
	    	}
        }
	}
	
	private void processLogicalLevel(FileAction a, List<Hunk> hunks) {
		//NOTE: it goes across method and file boundaries
		//      -> causes may be in a different artifact state 
		
		//get candidate code entity states for action file
		List<CodeEntityState> methodStates = datastore.find(CodeEntityState.class)
			.field("commit_id").equal(a.getCommitId())
			.field("file_id").equal(a.getFileId())
			.field("ce_type").equal("method")
			.asList();

		LinkedHashMap<Integer, Hunk> linesPost = new LinkedHashMap<>();
		for (Hunk h : hunks) {
			for (int line = h.getNewStart(); line < h.getNewStart()+h.getNewLines(); line++) {
				linesPost.put(line, h);
			}
		}

		for (CodeEntityState mState : methodStates) {
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
			
			logger.info("  "
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
		
        CFAState lState = getCFAStateForEntity(s.getId());
        if (lState == null) {
        	CFAState fState = getCFAStateForEntity(a.getId());
        	lState = new CFAState();
            lState.setType("method");
            lState.setEntityId(s.getId());
            lState.setParentId(fState.getId());
            targetstore.save(lState);
            cfaCache.put(lState.getId(), lState);
            cfaEntityCache.put(lState.getEntityId(), lState);
            fState.getChildrenIds().add(lState.getId());
        }
        
		for (Hunk h : hitHunks) {
			for (HunkBlameLine hbl : getHunkBlameLines(h)) {
				List<CodeEntityState> cms = getCausingStates(hbl);
				//compare db against local version -> identical outcome
				//TODO: benchmark
				
	            for (CodeEntityState cState : cms) {
					CFAState lCause = getCFAStateForEntity(cState.getId());
	            	lState.getFixesIds().add(lCause.getId());
	            	lCause.getCausesIds().add(lState.getId());
	            	
	            	logger.info("  -> "
            			+cState.getStartLine()
            			+"-"+cState.getEndLine()
            			+" ["+hbl.getSourceLine()+"-"+(hbl.getSourceLine()+hbl.getLineCount())+"]"
            			+" "+cState.getLongName()
            			+" @ "+getCommit(hbl.getBlamedCommitId()).getRevisionHash().substring(0,8));
	            }
			}
		}
	}

	private List<CodeEntityState> getCausingStatesLocal(HunkBlameLine hbl) {
		File f = getFile(hbl.getSourcePath());
		
		List<CodeEntityState> allCauseMethodStates = datastore.find(CodeEntityState.class)
			.field("commit_id").equal(hbl.getBlamedCommitId())
			.field("file_id").equal(f.getId())
			.field("ce_type").equal("method")
			.asList();
		
		List<CodeEntityState> cms = allCauseMethodStates.stream().filter(e -> 
			(hbl.getSourceLine() <= e.getStartLine() &&
			hbl.getSourceLine()+hbl.getLineCount() >= e.getStartLine()) ||
			(hbl.getSourceLine() >= e.getStartLine() &&
			hbl.getSourceLine() <= e.getEndLine())).collect(Collectors.toList());
		
		return cms;
	}

	private List<CodeEntityState> getCausingStates(HunkBlameLine hbl) {
		File f = getFile(hbl.getSourcePath());
		
		Query<CodeEntityState> cmsQuery = datastore.find(CodeEntityState.class)
			.field("commit_id").equal(hbl.getBlamedCommitId())
			.field("file_id").equal(f.getId())
			.field("ce_type").equal("method");
		
		cmsQuery.or(
			cmsQuery.and(
				cmsQuery.criteria("start_line").greaterThanOrEq(hbl.getSourceLine()),
				cmsQuery.criteria("start_line").lessThanOrEq(hbl.getSourceLine()+hbl.getLineCount())
			),
			cmsQuery.and(
				cmsQuery.criteria("start_line").lessThanOrEq(hbl.getSourceLine()),
				cmsQuery.criteria("end_line").greaterThanOrEq(hbl.getSourceLine())
			)
		);
		
		return cmsQuery.asList();
	}
	
	List<HunkBlameLine> getHunkBlameLines(Hunk h) {
		if (!hblCache.containsKey(h.getId())) {
			List<HunkBlameLine> blameLines = targetstore.find(HunkBlameLine.class)
    			.field("hunk_id").equal(h.getId()).asList();
			hblCache.put(h.getId(), blameLines);
		}
		return hblCache.get(h.getId());
	}


	File getFile(String path) {
		if (!fileCache.containsKey(path)) {
			File file = datastore.find(File.class)
				.field("vcs_system_id").equal(vcs.getId())
				.field("path").equal(path).get();
			fileCache.put(path, file);
		}
		return fileCache.get(path);
	}

	Commit getCommit(String hash) {
		if (!commitCache.containsKey(hash)) {
			Commit commit = datastore.find(Commit.class)
				.field("vcs_system_id").equal(vcs.getId())
				.field("revision_hash").equal(hash).get();
			commitCache.put(hash, commit);
			commitIdCache.put(commit.getId(), commit);
		}
		return commitCache.get(hash);
	}
	
	Commit getCommit(ObjectId id) {
		if (!commitIdCache.containsKey(id)) {
			Commit commit = datastore.get(Commit.class, id);
			commitCache.put(commit.getRevisionHash(), commit);
			commitIdCache.put(id, commit);
		}
		return commitIdCache.get(id);
	}

	CFAState getCFAStateForEntity(ObjectId id) {
		if (!cfaEntityCache.containsKey(id)) {
			CFAState state = targetstore.find(CFAState.class)
				.field("entity_id").equal(id)
				.get();
			if (state == null) {
				return state;
			}
			cfaEntityCache.put(id, state);
			cfaCache.put(state.getId(), state);
		}
		return cfaEntityCache.get(id);
	}
	
}
