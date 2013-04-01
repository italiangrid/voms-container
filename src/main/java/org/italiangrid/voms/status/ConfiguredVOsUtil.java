package org.italiangrid.voms.status;

import java.io.File;
import java.io.FileFilter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.italiangrid.voms.container.SysconfigUtil;

public class ConfiguredVOsUtil {

	private ConfiguredVOsUtil() {

	}

	public static List<String> getConfiguredVONames() {

		List<String> voNames = new ArrayList<String>();

		File confDir = new File(SysconfigUtil.getConfDir());

		if (confDir.exists() && confDir.isDirectory() && confDir.canExecute()
			&& confDir.canRead()) {

			File[] voFiles = confDir.listFiles(new FileFilter() {

				@Override
				public boolean accept(File pathname) {

					return pathname.isDirectory();
				}
			});

			for (File f : voFiles)
				voNames.add(f.getName());

		}
		
		Collections.sort(voNames);
		return voNames;
	}
}
