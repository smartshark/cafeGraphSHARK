package common.cafe;

import org.apache.commons.cli.Option;

import common.OptionHandler;

/**
 * In this class options representing all possible program arguments are defined.
 *
 * @author <a href="mailto:dhonsel@informatik.uni-goettingen.de">Daniel Honsel</a>
 * @author <a href="mailto:makedonski@informatik.uni-goettingen.de">Philip Makedonski</a>
 */

public class CafeGraphOptionHandler extends OptionHandler {

	@Override
	protected void initOptions() {
		super.initOptions();
	    Option option;

		option = new Option("M", "Process merges. Default: No");
	    option.setRequired(false);
	    option.setLongOpt("merges");
	    option.setArgs(0);
	    options.addOption(option);
	    
	}
}
