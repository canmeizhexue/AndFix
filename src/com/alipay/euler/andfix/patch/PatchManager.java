/*
 * 
 * Copyright (c) 2015, alipay.com
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.alipay.euler.andfix.patch;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import com.alipay.euler.andfix.AndFixManager;
import com.alipay.euler.andfix.util.FileUtil;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListSet;

/**
 * patch manager
 * 
 * @author sanping.li@alipay.com
 * 对多个修复包进行管理，
 */
public class PatchManager {
	private static final String TAG = "PatchManager";
	// patch extension
	private static final String SUFFIX = ".apatch";
	private static final String DIR = "apatch";
	private static final String SP_NAME = "_andfix_";
	private static final String SP_VERSION = "version";

	/**
	 * context
	 */
	private final Context mContext;
	/**
	 * AndFix manager
	 */
	private final AndFixManager mAndFixManager;
	/**
	 * patch directory
	 */
	private final File mPatchDir;
	/**
	 * patchs
	 */
	private final SortedSet<Patch> mPatchs;
	/**
	 * classloaders
	 */
	private final Map<String, ClassLoader> mLoaders;

	/**
	 * @param context
	 *            context
	 */
	public PatchManager(Context context) {
		mContext = context;
		mAndFixManager = new AndFixManager(mContext);
		//修复包最后放在沙盒的特定目录里面，，，，/data/data/xxx/files/apatch/xxx.apatch
		mPatchDir = new File(mContext.getFilesDir(), DIR);
		mPatchs = new ConcurrentSkipListSet<Patch>();
		mLoaders = new ConcurrentHashMap<String, ClassLoader>();
	}

	/**
	 * initialize
	 * 
	 * @param appVersion
	 *            App version
	 */
	public void init(String appVersion) {
		if (!mPatchDir.exists() && !mPatchDir.mkdirs()) {// make directory fail
			Log.e(TAG, "patch dir create error.");
			return;
		} else if (!mPatchDir.isDirectory()) {// not directory
			mPatchDir.delete();
			return;
		}
		SharedPreferences sp = mContext.getSharedPreferences(SP_NAME,
				Context.MODE_PRIVATE);
		String ver = sp.getString(SP_VERSION, null);
		if (ver == null || !ver.equalsIgnoreCase(appVersion)) {
			//俩个的版本不一致，清空之前的补丁，
			cleanPatch();
			sp.edit().putString(SP_VERSION, appVersion).commit();
		} else {
			//加载沙盒中的所有补丁包，
			initPatchs();
		}
	}

	private void initPatchs() {
		File[] files = mPatchDir.listFiles();
		for (File file : files) {
			addPatch(file);
		}
	}

	/**
	 * add patch file
	 * 
	 * @param file
	 * @return patch
	 */
	private Patch addPatch(File file) {
		Patch patch = null;
		if (file.getName().endsWith(SUFFIX)) {
			try {
				patch = new Patch(file);
				mPatchs.add(patch);
			} catch (IOException e) {
				Log.e(TAG, "addPatch", e);
			}
		}
		return patch;
	}

	private void cleanPatch() {
		File[] files = mPatchDir.listFiles();
		for (File file : files) {
			mAndFixManager.removeOptFile(file);
			if (!FileUtil.deleteFile(file)) {
				Log.e(TAG, file.getName() + " delete error.");
			}
		}
	}

	/**主要用在app正在运行的时候，下载补丁包，然后将它加载进来
	 * add patch at runtime
	 * 
	 * @param path
	 *            patch path
	 * @throws IOException
	 */
	public void addPatch(String path) throws IOException {
		File src = new File(path);
		File dest = new File(mPatchDir, src.getName());
		if(!src.exists()){
			throw new FileNotFoundException(path);
		}
		if (dest.exists()) {
			Log.d(TAG, "patch [" + path + "] has be loaded.");
			return;
		}
		FileUtil.copyFile(src, dest);// copy to patch's directory
		Patch patch = addPatch(dest);
		if (patch != null) {
			loadPatch(patch);
		}
	}

	/**
	 * remove all patchs
	 */
	public void removeAllPatch() {
		cleanPatch();
		SharedPreferences sp = mContext.getSharedPreferences(SP_NAME,
				Context.MODE_PRIVATE);
		sp.edit().clear().commit();
	}

	/**
	 * load patch,call when plugin be loaded. used for plugin architecture.</br>
	 * 
	 * need name and classloader of the plugin
	 * 
	 * @param patchName
	 *            patch name
	 * @param classLoader
	 *            classloader
	 */
	public void loadPatch(String patchName, ClassLoader classLoader) {
		mLoaders.put(patchName, classLoader);
		Set<String> patchNames;
		List<String> classes;
		for (Patch patch : mPatchs) {
			patchNames = patch.getPatchNames();
			if (patchNames.contains(patchName)) {
				classes = patch.getClasses(patchName);
				mAndFixManager.fix(patch.getFile(), classLoader, classes);
			}
		}
	}

	/**
	 * load patch,call when application start
	 * 
	 */
	public void loadPatch() {
		mLoaders.put("*", mContext.getClassLoader());// wildcard
		Set<String> patchNames;
		List<String> classes;
		for (Patch patch : mPatchs) {
			patchNames = patch.getPatchNames();
			for (String patchName : patchNames) {
				classes = patch.getClasses(patchName);
				mAndFixManager.fix(patch.getFile(), mContext.getClassLoader(),
						classes);
			}
		}
	}

	/**将补丁包中的类加载进来，并且替换要修复的方法
	 * load specific patch
	 * 
	 * @param patch
	 *            patch
	 */
	private void loadPatch(Patch patch) {
		Set<String> patchNames = patch.getPatchNames();
		ClassLoader cl;
		List<String> classes;
		for (String patchName : patchNames) {
			if (mLoaders.containsKey("*")) {
				cl = mContext.getClassLoader();
			} else {
				cl = mLoaders.get(patchName);
			}
			if (cl != null) {
				classes = patch.getClasses(patchName);
				mAndFixManager.fix(patch.getFile(), cl, classes);
			}
		}
	}

}
