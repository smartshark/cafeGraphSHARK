package common.cafe;

import java.util.ArrayList;
import java.util.Properties;

import common.ConfigurationHandler;

public class CafeGraphConfigurationHandler extends ConfigurationHandler {
	private static CafeGraphConfigurationHandler instance;

	public static synchronized CafeGraphConfigurationHandler getInstance() {
		if (instance == null) {
			instance = new CafeGraphConfigurationHandler();
		}
		return instance;
	}	

	@Override
	protected ArrayList<String> convertProperties(Properties properties) {
		ArrayList<String> props = new ArrayList<>();
		props.addAll(super.convertProperties(properties));
		props.add("-u");
		props.add(properties.getProperty("url"));
		props.add("-i");
		props.add(properties.getProperty("git"));
		if (properties.containsKey("revision")) {
			props.add("-r");
			props.add(properties.getProperty("revision"));
		}
		return props;
	}
}
