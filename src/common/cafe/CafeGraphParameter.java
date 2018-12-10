package common.cafe;

import common.Parameter;

public class CafeGraphParameter extends Parameter {
	protected String version = "0.10";
	protected static CafeGraphParameter instance;

	// required parameter
	private String repoPath;
	private String url;

	// optional
	private String commit;
	private boolean processMerges;
	
	public static synchronized CafeGraphParameter getInstance() {
		if (instance == null) {
			instance = new CafeGraphParameter();
		    instance.setOptionsHandler(new CafeGraphOptionHandler());
		    instance.setToolname("cafeSHARK");
		}
		return instance;
	}	
	
	@Override
	public void init(String args[]) {
		super.init(args);
		repoPath = cmd.getOptionValue("i");
		commit = cmd.getOptionValue("r");
		url = cmd.getOptionValue("u");
		processMerges = cmd.hasOption("M");
	}
	
	@Override
	protected void checkArguments() {
		super.checkArguments();
		if (!cmd.hasOption("u")  && !cmd.hasOption("i")) {
	        System.err.println("ERROR: Missing required options: u, i");
	        printHelp();
	        System.exit(1);
	    }
	}
	
	public String getRepoPath() {
	    checkIfInitialised();
	    return repoPath;
	}

	public String getCommit() {
	    checkIfInitialised();
	    return commit;
	}

	public String getUrl() {
	    checkIfInitialised();
	    return url;
	}

	public boolean isProcessMerges() {
	    checkIfInitialised();
		return processMerges;
	}

}
