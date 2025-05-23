/*******************************************************************************
 * Copyright (c) 2021, 2024 Red Hat Inc. and others.
 *
 * This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License 2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 *     Red Hat Inc. - initial API and implementation
 *******************************************************************************/
package org.eclipse.pde.unittest.junit.launcher;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.net.MalformedURLException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.Assert;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.FileLocator;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.SubMonitor;
import org.eclipse.core.runtime.URIUtil;
import org.eclipse.core.variables.VariablesPlugin;
import org.eclipse.debug.core.DebugPlugin;
import org.eclipse.debug.core.ILaunch;
import org.eclipse.debug.core.ILaunchConfiguration;
import org.eclipse.debug.core.ILaunchConfigurationWorkingCopy;
import org.eclipse.debug.core.ILaunchManager;
import org.eclipse.jdt.core.IClasspathAttribute;
import org.eclipse.jdt.core.IClasspathEntry;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IMember;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.IPackageFragment;
import org.eclipse.jdt.core.IPackageFragmentRoot;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jdt.internal.junit.launcher.ITestKind;
import org.eclipse.jdt.internal.junit.launcher.JUnitLaunchConfigurationConstants;
import org.eclipse.jdt.internal.junit.launcher.JUnitRuntimeClasspathEntry;
import org.eclipse.jdt.launching.AbstractJavaLaunchConfigurationDelegate;
import org.eclipse.jdt.launching.ExecutionArguments;
import org.eclipse.jdt.launching.IJavaLaunchConfigurationConstants;
import org.eclipse.jdt.launching.IVMInstall;
import org.eclipse.jdt.launching.IVMRunner;
import org.eclipse.jdt.launching.JavaRuntime;
import org.eclipse.jdt.launching.SocketUtil;
import org.eclipse.jdt.launching.VMRunnerConfiguration;
import org.eclipse.jdt.ui.unittest.junit.JUnitTestPlugin;
import org.eclipse.jdt.ui.unittest.junit.JUnitTestPlugin.JUnitVersion;
import org.eclipse.osgi.service.resolver.BundleDescription;
import org.eclipse.osgi.util.NLS;
import org.eclipse.pde.core.plugin.IFragmentModel;
import org.eclipse.pde.core.plugin.IPluginModelBase;
import org.eclipse.pde.core.plugin.PluginRegistry;
import org.eclipse.pde.core.plugin.TargetPlatform;
import org.eclipse.pde.internal.core.ClasspathHelper;
import org.eclipse.pde.internal.core.DependencyManager;
import org.eclipse.pde.internal.core.ICoreConstants;
import org.eclipse.pde.internal.core.PDECore;
import org.eclipse.pde.internal.core.TargetPlatformHelper;
import org.eclipse.pde.internal.core.util.CoreUtility;
import org.eclipse.pde.internal.launching.IPDEConstants;
import org.eclipse.pde.internal.launching.launcher.BundleLauncherHelper;
import org.eclipse.pde.internal.launching.launcher.EclipsePluginValidationOperation;
import org.eclipse.pde.internal.launching.launcher.LaunchArgumentsHelper;
import org.eclipse.pde.internal.launching.launcher.LaunchConfigurationHelper;
import org.eclipse.pde.internal.launching.launcher.LaunchPluginValidator;
import org.eclipse.pde.internal.launching.launcher.LauncherUtils;
import org.eclipse.pde.internal.launching.launcher.RequirementHelper;
import org.eclipse.pde.internal.launching.launcher.VMHelper;
import org.eclipse.pde.launching.IPDELauncherConstants;
import org.eclipse.pde.launching.JUnitLaunchConfigurationDelegate;
import org.eclipse.pde.launching.PDESourcePathProvider;
import org.eclipse.pde.unittest.junit.JUnitPluginTestPlugin;
import org.osgi.framework.Bundle;
import org.osgi.framework.Constants;

/**
 * Launch configuration delegate for a JUnit test as a Java application.
 *
 * <p>
 * Clients can instantiate and extend this class.
 * </p>
 */
public class JUnitPluginLaunchConfigurationDelegate extends AbstractJavaLaunchConfigurationDelegate {

	static {
		RequirementHelper.registerSameRequirementsAsFor("org.eclipse.pde.unittest.junit.launchConfiguration", //$NON-NLS-1$
				"org.eclipse.pde.ui.JunitLaunchConfig"); //$NON-NLS-1$
	}

	// This needs to be differnet from JunitLaunchConfigurationConstants.ATTR_PORT
	// or the "legacy" view handles it first
	public static final String ATTR_PORT = JUnitPluginTestPlugin.PLUGIN_ID + ".PORT"; //$NON-NLS-1$

	private boolean fKeepAlive = false;
	private int fPort;
	private IJavaElement[] fTestElements;

	private static final String DEFAULT = "<default>"; //$NON-NLS-1$

	@Override
	public ILaunch getLaunch(ILaunchConfiguration configuration, String mode) throws CoreException {
		JUnitPluginTestPlugin.activateUnitTestCoreBundle();
		return super.getLaunch(configuration, mode);
	}

	@Override
	public String showCommandLine(ILaunchConfiguration configuration, String mode, ILaunch launch,
			IProgressMonitor monitor) throws CoreException {
		launch.setAttribute(PDE_JUNIT_SHOW_COMMAND, "true"); //$NON-NLS-1$

		if (monitor == null) {
			monitor = new NullProgressMonitor();
		}
		try {
			VMRunnerConfiguration runConfig = getVMRunnerConfiguration(configuration, launch, mode, monitor);
			if (runConfig == null) {
				return ""; //$NON-NLS-1$
			}
			IVMRunner runner = getVMRunner(configuration, mode);
			String cmdLine = runner.showCommandLine(runConfig, launch, monitor);

			// check for cancellation
			if (monitor.isCanceled()) {
				return ""; //$NON-NLS-1$
			}
			return cmdLine;
		} finally {
			monitor.done();
		}
	}

	private VMRunnerConfiguration getVMRunnerConfiguration(ILaunchConfiguration configuration, ILaunch launch,
			String mode, IProgressMonitor monitor) throws CoreException {
		VMRunnerConfiguration runConfig = null;
		monitor.beginTask(MessageFormat.format("{0}...", configuration.getName()), 5); //$NON-NLS-1$
		// check for cancellation
		if (monitor.isCanceled()) {
			return null;
		}

		try {
			if (mode.equals(JUnitLaunchConfigurationConstants.MODE_RUN_QUIETLY_MODE)) {
				launch.setAttribute(JUnitLaunchConfigurationConstants.ATTR_NO_DISPLAY, "true"); //$NON-NLS-1$
				mode = ILaunchManager.RUN_MODE;
			}

			monitor.subTask(Messages.JUnitPluginLaunchConfigurationDelegate_verifying_attriburtes_description);

			try {
				preLaunchCheck(configuration, launch, SubMonitor.convert(monitor, 2));
			} catch (CoreException e) {
				if (e.getStatus().getSeverity() == IStatus.CANCEL) {
					monitor.setCanceled(true);
					return null;
				}
				throw e;
			}
			// check for cancellation
			if (monitor.isCanceled()) {
				return null;
			}

			fKeepAlive = mode.equals(ILaunchManager.DEBUG_MODE)
					&& configuration.getAttribute(JUnitLaunchConfigurationConstants.ATTR_KEEPRUNNING, false);
			fPort = evaluatePort();
			launch.setAttribute(ATTR_PORT, String.valueOf(fPort));

			JUnitVersion junitVersion = getJUnitVersion(configuration);
			IJavaProject javaProject = getJavaProject(configuration);
			if (junitVersion == JUnitVersion.JUNIT3 || junitVersion == JUnitVersion.JUNIT4) {
				fTestElements = evaluateTests(configuration, SubMonitor.convert(monitor, 1));
			} else {
				IJavaElement testTarget = getTestTarget(configuration, javaProject);
				if (testTarget instanceof IPackageFragment || testTarget instanceof IPackageFragmentRoot
						|| testTarget instanceof IJavaProject) {
					fTestElements = new IJavaElement[] { testTarget };
				} else {
					fTestElements = evaluateTests(configuration, SubMonitor.convert(monitor, 1));
				}
			}

			String mainTypeName = verifyMainTypeName(configuration);

			File workingDir = verifyWorkingDirectory(configuration);
			String workingDirName = null;
			if (workingDir != null) {
				workingDirName = workingDir.getAbsolutePath();
			}

			// Environment variables
			String[] envp = getEnvironment(configuration);

			ArrayList<String> vmArguments = new ArrayList<>();
			ArrayList<String> programArguments = new ArrayList<>();
			collectExecutionArguments(configuration, vmArguments, programArguments);
			vmArguments.addAll(Arrays.asList(DebugPlugin.parseArguments(getVMArguments(configuration, mode))));
			if (JavaRuntime.isModularProject(javaProject)) {
				vmArguments.add("--add-modules=ALL-MODULE-PATH"); //$NON-NLS-1$
			}

			// VM-specific attributes

			Map<String, Object> vmAttributesMap = getVMSpecificAttributesMap(configuration);

			// Classpath and modulepath
			String[][] classpathAndModulepath = getClasspathAndModulepath(configuration);
			String[] classpath = classpathAndModulepath[0];
			String[] modulepath = classpathAndModulepath[1];

			if (junitVersion == JUnitVersion.JUNIT5) {
				if (!configuration.getAttribute(
						JUnitLaunchConfigurationConstants.ATTR_DONT_ADD_MISSING_JUNIT5_DEPENDENCY, false)) {
					if (!Arrays.stream(classpath).anyMatch(
							s -> s.contains("junit-platform-launcher") || s.contains("org.junit.platform.launcher"))) { //$NON-NLS-1$ //$NON-NLS-2$
						try {
							JUnitRuntimeClasspathEntry x = new JUnitRuntimeClasspathEntry("junit-platform-launcher", //$NON-NLS-1$
									null);
							String entryString = new ClasspathLocalizer(Platform.inDevelopmentMode()).entryString(x);
							int length = classpath.length;
							System.arraycopy(classpath, 0, classpath = new String[length + 1], 0, length);
							classpath[length] = entryString;
						} catch (IOException | URISyntaxException e) {
							throw new CoreException(Status.error("", e)); //$NON-NLS-1$
						}
					}
				}
			}

			// Create VM config
			runConfig = new VMRunnerConfiguration(mainTypeName, classpath);
			runConfig.setVMArguments(vmArguments.toArray(new String[vmArguments.size()]));
			runConfig.setProgramArguments(programArguments.toArray(new String[programArguments.size()]));
			runConfig.setEnvironment(envp);
			runConfig.setWorkingDirectory(workingDirName);
			runConfig.setVMSpecificAttributesMap(vmAttributesMap);
			runConfig.setPreviewEnabled(supportsPreviewFeatures(configuration));

			if (!JavaRuntime.isModularConfiguration(configuration)) {
				// Bootpath
				runConfig.setBootClassPath(getBootpath(configuration));
			} else {
				// module path
				runConfig.setModulepath(modulepath);
				if (!configuration.getAttribute(IJavaLaunchConfigurationConstants.ATTR_DEFAULT_MODULE_CLI_OPTIONS,
						true)) {
					runConfig.setOverrideDependencies(
							configuration.getAttribute(IJavaLaunchConfigurationConstants.ATTR_MODULE_CLI_OPTIONS, "")); //$NON-NLS-1$
				} else {
					runConfig.setOverrideDependencies(getModuleCLIOptions(configuration));
				}
			}

			// check for cancellation
			if (monitor.isCanceled()) {
				return null;
			}
		} finally {
			// done the verification phase
			monitor.worked(1);
		}
		return runConfig;
	}

	static JUnitVersion getJUnitVersion(ILaunchConfiguration configuration) {
		try {
			String junitTestKindId = configuration.getAttribute(JUnitLaunchConfigurationConstants.ATTR_TEST_RUNNER_KIND,
					""); //$NON-NLS-1$
			if (!junitTestKindId.isEmpty()) {
				return JUnitVersion.fromJUnitTestKindId(junitTestKindId);
			}
		} catch (Exception ex) {
			JUnitPluginTestPlugin.log(ex);
		}
		IJavaProject javaProject = JUnitLaunchConfigurationConstants.getJavaProject(configuration);
		if (javaProject != null) {
			return JUnitTestPlugin.getJUnitVersion(javaProject);
		}
		return JUnitVersion.JUNIT3;
	}

	@Override
	public synchronized void launch(ILaunchConfiguration configuration, String mode, ILaunch launch,
			IProgressMonitor monitor) throws CoreException {
		if (monitor == null) {
			monitor = new NullProgressMonitor();
		}

		try {

			VMRunnerConfiguration runConfig = getVMRunnerConfiguration(configuration, launch, mode, monitor);
			if (monitor.isCanceled() || runConfig == null) {
				return;
			}
			IVMRunner runner = getVMRunner(configuration, mode);
			monitor.subTask(Messages.JUnitPluginLaunchConfigurationDelegate_create_source_locator_description);
			// set the default source locator if required
			setDefaultSourceLocator(launch, configuration);
			monitor.worked(1);

			// Launch the configuration - 1 unit of work
			runner.run(runConfig, launch, monitor);
		} finally {
			fTestElements = null;
			monitor.done();
		}
	}

	private int evaluatePort() throws CoreException {
		int port = SocketUtil.findFreePort();
		if (port == -1) {
			abort(Messages.JUnitPluginLaunchConfigurationDelegate_error_no_socket, null,
					IJavaLaunchConfigurationConstants.ERR_NO_SOCKET_AVAILABLE);
		}
		return port;
	}

	/**
	 * Performs a check on the launch configuration's attributes. If an attribute
	 * contains an invalid value, a {@link CoreException} with the error is thrown.
	 *
	 * @param configuration the launch configuration to verify
	 * @param launch        the launch to verify
	 * @param monitor       the progress monitor to use
	 * @throws CoreException an exception is thrown when the verification fails
	 */
	protected void preLaunchCheck(ILaunchConfiguration configuration, ILaunch launch, IProgressMonitor monitor)
			throws CoreException {
		launchMode = launch.getLaunchMode();
		fWorkspaceLocation = null;
		fConfigDir = null;
		fModels = BundleLauncherHelper.getMergedBundleMap(configuration, false);
		fAllBundles = fModels.keySet().stream().collect(Collectors.groupingBy(m -> m.getPluginBase().getId(),
				LinkedHashMap::new, Collectors.toCollection(ArrayList::new)));

		// implicitly add the plug-ins required for JUnit testing if necessary
		addRequiredJunitRuntimePlugins(configuration);

		String attribute = launch.getAttribute(PDE_JUNIT_SHOW_COMMAND);
		boolean isShowCommand = false;
		if (attribute != null) {
			isShowCommand = attribute.equals("true"); //$NON-NLS-1$
		}
		boolean autoValidate = configuration.getAttribute(IPDELauncherConstants.AUTOMATIC_VALIDATE, false);
		SubMonitor subMonitor = SubMonitor.convert(monitor, autoValidate ? 3 : 4);
		if (isShowCommand == false) {
			if (autoValidate) {
				validatePluginDependencies(configuration, subMonitor.split(1));
			}
			validateProjectDependencies(configuration, subMonitor.split(1));
			clear(configuration, subMonitor.split(1));
		}
		launch.setAttribute(PDE_JUNIT_SHOW_COMMAND, "false"); //$NON-NLS-1$
		launch.setAttribute(IPDELauncherConstants.CONFIG_LOCATION, getConfigurationDirectory(configuration).toString());
		synchronizeManifests(configuration, subMonitor.split(1));
	}

	private void addRequiredJunitRuntimePlugins(ILaunchConfiguration configuration) throws CoreException {
		Set<String> requiredPlugins = new LinkedHashSet<>(
				JUnitLaunchConfigurationDelegate.getRequiredJunitRuntimePlugins(configuration));

		if (fAllBundles.containsKey("junit-platform-runner")) { //$NON-NLS-1$
			// add launcher and jupiter.engine to support @RunWith(JUnitPlatform.class)
			requiredPlugins.add("junit-platform-launcher"); //$NON-NLS-1$
			requiredPlugins.add("junit-jupiter-engine"); //$NON-NLS-1$
		}

		Set<BundleDescription> addedRequirements = new HashSet<>();
		addAbsentRequirements(requiredPlugins, addedRequirements);

		Set<BundleDescription> requirementsOfRequirements = DependencyManager
				.findRequirementsClosure(addedRequirements);
		Set<String> rorIds = requirementsOfRequirements.stream().map(BundleDescription::getSymbolicName)
				.collect(Collectors.toSet());
		addAbsentRequirements(rorIds, null);
	}

	private void addAbsentRequirements(Collection<String> requirements, Set<BundleDescription> addedRequirements)
			throws CoreException {
		for (String id : requirements) {
			List<IPluginModelBase> models = fAllBundles.computeIfAbsent(id, k -> new ArrayList<>());
			if (models.stream().noneMatch(m -> m.getBundleDescription().isResolved())) {
				IPluginModelBase model = findRequiredPluginInTargetOrHost(id);
				models.add(model);
				BundleLauncherHelper.addDefaultStartingBundle(fModels, model);
				if (addedRequirements != null) {
					addedRequirements.add(model.getBundleDescription());
				}
			}
		}
	}

	@Override
	public String getJavaProjectName(ILaunchConfiguration configuration) throws CoreException {
		return configuration.getAttribute(IJavaLaunchConfigurationConstants.ATTR_PROJECT_NAME, (String) null);
	}

	@Override
	public String getMainTypeName(ILaunchConfiguration configuration) throws CoreException {
		String mainType = configuration.getAttribute(IJavaLaunchConfigurationConstants.ATTR_MAIN_TYPE_NAME,
				(String) null);
		if (mainType == null) {
			return null;
		}
		return VariablesPlugin.getDefault().getStringVariableManager().performStringSubstitution(mainType);
	}

	@Override
	public String verifyMainTypeName(ILaunchConfiguration configuration) throws CoreException {
		if (TargetPlatformHelper.getTargetVersion() >= 3.3) {
			return "org.eclipse.equinox.launcher.Main"; //$NON-NLS-1$
		}
		return "org.eclipse.core.launcher.Main"; //$NON-NLS-1$
	}

	/**
	 * Evaluates all test elements selected by the given launch configuration. The
	 * elements are of type {@link IType} or {@link IMethod}. At the moment it is
	 * only possible to run a single method or a set of types, but not mixed or more
	 * than one method at a time.
	 *
	 * @param configuration the launch configuration to inspect
	 * @param monitor       the progress monitor
	 * @return returns all types or methods that should be ran
	 * @throws CoreException an exception is thrown when the search for tests failed
	 */
	protected IMember[] evaluateTests(ILaunchConfiguration configuration, IProgressMonitor monitor)
			throws CoreException {
		IJavaProject javaProject = getJavaProject(configuration);

		IJavaElement testTarget = getTestTarget(configuration, javaProject);
		String testMethodName = configuration.getAttribute(JUnitLaunchConfigurationConstants.ATTR_TEST_NAME, ""); //$NON-NLS-1$
		if (testMethodName.length() > 0) {
			if (testTarget instanceof IType) {
				// If parameters exist, testMethodName is followed by a comma-separated list of
				// fully qualified parameter type names in parentheses.
				// The testMethodName is required in this format by #collectExecutionArguments,
				// hence it will be used as it is with the handle-only method IType#getMethod
				// here.
				return new IMember[] { ((IType) testTarget).getMethod(testMethodName, new String[0]) };
			}
		}
		HashSet<IType> result = new HashSet<>();
		org.eclipse.jdt.internal.junit.launcher.ITestKind junitTestKind = getJUnitVersion(configuration)
				.getJUnitTestKind();
		junitTestKind.getFinder().findTestsInContainer(testTarget, result, monitor);
		if (result.isEmpty()) {
			String msg = MessageFormat.format(Messages.JUnitPluginLaunchConfigurationDelegate_error_notests_kind,
					junitTestKind.getDisplayName());
			abort(msg, null, IJavaLaunchConfigurationConstants.ERR_UNSPECIFIED_MAIN_TYPE);
		}
		return result.toArray(new IMember[result.size()]);
	}

	/**
	 * Collects all VM and program arguments. Implementors can modify and add
	 * arguments.
	 *
	 * @param configuration the configuration to collect the arguments for
	 * @param vmArguments   a {@link List} of {@link String} representing the
	 *                      resulting VM arguments
	 * @param programArgs   a {@link List} of {@link String} representing the
	 *                      resulting program arguments
	 * @exception CoreException if unable to collect the execution arguments
	 */
	protected void collectExecutionArguments(ILaunchConfiguration configuration, List<String> vmArguments,
			List<String> programArgs) throws CoreException {
		internalCollectExecutionArguments(configuration, vmArguments, programArgs);

		// Specify the JUnit Plug-in test application to launch
		programArgs.add("-application"); //$NON-NLS-1$
		String application = getApplication(configuration);

		programArgs.add(application);

		// If a product is specified, then add it to the program args
		if (configuration.getAttribute(IPDELauncherConstants.USE_PRODUCT, false)) {
			programArgs.add("-product"); //$NON-NLS-1$
			programArgs.add(configuration.getAttribute(IPDELauncherConstants.PRODUCT, "")); //$NON-NLS-1$
		} else {
			// Specify the application to test
			String defaultApplication = TargetPlatform.getDefaultApplication();
			if (IPDEConstants.CORE_TEST_APPLICATION.equals(application)) {
				// If we are launching the core test application we don't need a test app
				defaultApplication = null;
			} else if (IPDEConstants.NON_UI_THREAD_APPLICATION.equals(application)) {
				// When running in a non-UI thread, run the core test app to avoid opening the
				// workbench
				defaultApplication = IPDEConstants.CORE_TEST_APPLICATION;
			}

			String testApplication = configuration.getAttribute(IPDELauncherConstants.APP_TO_TEST, defaultApplication);
			if (testApplication != null) {
				programArgs.add("-testApplication"); //$NON-NLS-1$
				programArgs.add(testApplication);
			}
		}

		// Specify the location of the runtime workbench
		if (fWorkspaceLocation == null) {
			fWorkspaceLocation = LaunchArgumentsHelper.getWorkspaceLocation(configuration);
		}
		if (fWorkspaceLocation.length() > 0) {
			programArgs.add("-data"); //$NON-NLS-1$
			programArgs.add(fWorkspaceLocation);
		}

		// Create the platform configuration for the runtime workbench
		String productID = LaunchConfigurationHelper.getProductID(configuration);
		LaunchConfigurationHelper.createConfigIniFile(configuration, productID, fAllBundles, fModels,
				getConfigurationDirectory(configuration));
		TargetPlatformHelper.checkPluginPropertiesConsistency(fAllBundles, getConfigurationDirectory(configuration));

		programArgs.add("-configuration"); //$NON-NLS-1$
		programArgs.add("file:" + IPath.fromFile(getConfigurationDirectory(configuration)).addTrailingSeparator()); //$NON-NLS-1$

		// Specify the output folder names
		programArgs.add("-dev"); //$NON-NLS-1$
		programArgs.add(ClasspathHelper
				.getDevEntriesProperties(getConfigurationDirectory(configuration).toString() + "/dev.properties", //$NON-NLS-1$
						fAllBundles)
				.toUri().toString());

		// Create the .options file if tracing is turned on
		if (configuration.getAttribute(IPDELauncherConstants.TRACING, false) && !IPDELauncherConstants.TRACING_NONE
				.equals(configuration.getAttribute(IPDELauncherConstants.TRACING_CHECKED, (String) null))) {
			programArgs.add("-debug"); //$NON-NLS-1$
			Path path = getConfigurationDirectory(configuration).toPath().resolve(ICoreConstants.OPTIONS_FILENAME);
			programArgs.add(LaunchArgumentsHelper.getTracingFileArgument(configuration, path));
		}

		// add the program args specified by the user
		String[] userArgs = LaunchArgumentsHelper.getUserProgramArgumentArray(configuration);
		for (String userArg : userArgs) {
			// be forgiving if people have tracing turned on and forgot
			// to remove the -debug from the program args field.
			if (userArg.equals("-debug") && programArgs.contains("-debug")) { //$NON-NLS-1$ //$NON-NLS-2$
				continue;
			}
			programArgs.add(userArg);
		}

		if (!configuration.getAttribute(IPDEConstants.APPEND_ARGS_EXPLICITLY, false)) {
			if (!programArgs.contains("-os")) { //$NON-NLS-1$
				programArgs.add("-os"); //$NON-NLS-1$
				programArgs.add(TargetPlatform.getOS());
			}
			if (!programArgs.contains("-ws")) { //$NON-NLS-1$
				programArgs.add("-ws"); //$NON-NLS-1$
				programArgs.add(TargetPlatform.getWS());
			}
			if (!programArgs.contains("-arch")) { //$NON-NLS-1$
				programArgs.add("-arch"); //$NON-NLS-1$
				programArgs.add(TargetPlatform.getOSArch());
			}
		}

		programArgs.add("-testpluginname"); //$NON-NLS-1$
		programArgs.add(getTestPluginId(configuration));
		IVMInstall launcher = VMHelper.createLauncher(configuration, fModels.keySet());
		boolean isModular = JavaRuntime.isModularJava(launcher);
		if (isModular) {
			VMHelper.addNewArgument(vmArguments, "--add-modules", "ALL-SYSTEM"); //$NON-NLS-1$//$NON-NLS-2$
		}
		// if element is a test class annotated with @RunWith(JUnitPlatform.class, we
		// add this in program arguments
		if (configuration.getAttribute(JUnitLaunchConfigurationConstants.ATTR_RUN_WITH_JUNIT_PLATFORM_ANNOTATION,
				false)) {
			programArgs.add("-runasjunit5"); //$NON-NLS-1$
		}
	}

	private void internalCollectExecutionArguments(ILaunchConfiguration configuration, List<String> vmArguments,
			List<String> programArguments) throws CoreException {

		// add program & VM arguments provided by getProgramArguments and getVMArguments
		String pgmArgs = getProgramArguments(configuration);
		String vmArgs = getVMArguments(configuration);
		ExecutionArguments execArgs = new ExecutionArguments(vmArgs, pgmArgs);
		vmArguments.addAll(Arrays.asList(execArgs.getVMArgumentsArray()));
		programArguments.addAll(Arrays.asList(execArgs.getProgramArgumentsArray()));

		boolean isModularProject = JavaRuntime.isModularProject(getJavaProject(configuration));
		String addOpensTargets;
		if (isModularProject) {
			if (getJUnitVersion(configuration) == JUnitVersion.JUNIT5) {
				if (isOnModulePath(getJavaProject(configuration), "org.junit.jupiter.api.Test")) { //$NON-NLS-1$
					addOpensTargets = "junit-platform-commons,ALL-UNNAMED"; //$NON-NLS-1$
				} else {
					addOpensTargets = "ALL-UNNAMED"; //$NON-NLS-1$
				}
			} else {
				if (isOnModulePath(getJavaProject(configuration), "junit.framework.TestCase")) { //$NON-NLS-1$
					addOpensTargets = "junit,ALL-UNNAMED"; //$NON-NLS-1$
				} else {
					addOpensTargets = "ALL-UNNAMED"; //$NON-NLS-1$
				}
			}
		} else {
			addOpensTargets = null;
		}
		List<String> addOpensVmArgs = new ArrayList<>();

		/*
		 * The "-version" "3" arguments don't make sense and should eventually be
		 * removed. But we keep them for now, since users may want to run with older
		 * releases of org.eclipse.jdt.junit[4].runtime, where this is still read by
		 * org.eclipse.jdt.internal.junit.runner.RemoteTestRunner#defaultInit(String[])
		 * and used in
		 * org.eclipse.jdt.internal.junit.runner.DefaultClassifier#isComparisonFailure(
		 * Throwable). The JUnit4 equivalent of the latter method is already
		 * version-agnostic:
		 * org.eclipse.jdt.internal.junit4.runner.JUnit4TestListener#testFailure(
		 * Failure, boolean)
		 */
		programArguments.add("-version"); //$NON-NLS-1$
		programArguments.add("3"); //$NON-NLS-1$

		programArguments.add("-port"); //$NON-NLS-1$
		programArguments.add(String.valueOf(fPort));

		if (fKeepAlive) {
			programArguments.add(0, "-keepalive"); //$NON-NLS-1$
		}

		ITestKind testRunnerKind = getJUnitVersion(configuration).getJUnitTestKind();

		programArguments.add("-testLoaderClass"); //$NON-NLS-1$
		programArguments.add(testRunnerKind.getLoaderClassName());
		programArguments.add("-loaderpluginname"); //$NON-NLS-1$
		programArguments.add(testRunnerKind.getLoaderPluginId());

		// Enable Debugging mode:
		// programArguments.add("-debugging"); //$NON-NLS-1$

		IJavaElement[] testElements = fTestElements;

		if (testElements.length == 1) { // a test name was specified just run the single test, or a test container was
										// specified
			IJavaElement testElement = testElements[0];
			if (testElement instanceof IMethod method) {
				programArguments.add("-test"); //$NON-NLS-1$
				programArguments.add(method.getDeclaringType().getFullyQualifiedName() + ':' + method.getElementName());
				collectAddOpensVmArgs(addOpensTargets, addOpensVmArgs, method, configuration);
			} else if (testElement instanceof IType type) {
				programArguments.add("-classNames"); //$NON-NLS-1$
				programArguments.add(type.getFullyQualifiedName());
				collectAddOpensVmArgs(addOpensTargets, addOpensVmArgs, type, configuration);
			} else if (testElement instanceof IPackageFragment || testElement instanceof IPackageFragmentRoot
					|| testElement instanceof IJavaProject) {
				Set<String> pkgNames = new HashSet<>();
				String fileName = createPackageNamesFile(testElement, testRunnerKind, pkgNames);
				programArguments.add("-packageNameFile"); //$NON-NLS-1$
				programArguments.add(fileName);
				for (String pkgName : pkgNames) {
					if (!DEFAULT.equals(pkgName)) { // skip --add-opens for default package
						collectAddOpensVmArgs(addOpensTargets, addOpensVmArgs, pkgName, configuration);
					}
				}
			} else {
				abort(Messages.JUnitPluginLaunchConfigurationDelegate_error_wrong_input, null,
						IJavaLaunchConfigurationConstants.ERR_UNSPECIFIED_MAIN_TYPE);
			}
		} else if (testElements.length > 1) {
			String fileName = createTestNamesFile(testElements);
			programArguments.add("-testNameFile"); //$NON-NLS-1$
			programArguments.add(fileName);
			for (IJavaElement testElement : testElements) {
				collectAddOpensVmArgs(addOpensTargets, addOpensVmArgs, testElement, configuration);
			}
		}

		String testFailureNames = configuration.getAttribute(JUnitLaunchConfigurationConstants.ATTR_FAILURES_NAMES, ""); //$NON-NLS-1$
		if (testFailureNames.length() > 0) {
			programArguments.add("-testfailures"); //$NON-NLS-1$
			programArguments.add(testFailureNames);
		}

		String uniqueId = configuration.getAttribute(JUnitLaunchConfigurationConstants.ATTR_TEST_UNIQUE_ID, ""); //$NON-NLS-1$
		if (!uniqueId.trim().isEmpty()) {
			programArguments.add("-uniqueId"); //$NON-NLS-1$
			programArguments.add(uniqueId);
		}

		boolean hasIncludeTags = configuration
				.getAttribute(JUnitLaunchConfigurationConstants.ATTR_TEST_HAS_INCLUDE_TAGS, false);
		if (hasIncludeTags) {
			String includeTags = configuration.getAttribute(JUnitLaunchConfigurationConstants.ATTR_TEST_INCLUDE_TAGS,
					""); //$NON-NLS-1$
			if (includeTags != null && !includeTags.trim().isEmpty()) {
				String[] tags = includeTags.split(","); //$NON-NLS-1$
				for (String tag : tags) {
					programArguments.add("--include-tag"); //$NON-NLS-1$
					programArguments.add(tag.trim());
				}
			}
		}

		boolean hasExcludeTags = configuration
				.getAttribute(JUnitLaunchConfigurationConstants.ATTR_TEST_HAS_EXCLUDE_TAGS, false);
		if (hasExcludeTags) {
			String excludeTags = configuration.getAttribute(JUnitLaunchConfigurationConstants.ATTR_TEST_EXCLUDE_TAGS,
					""); //$NON-NLS-1$
			if (excludeTags != null && !excludeTags.trim().isEmpty()) {
				String[] tags = excludeTags.split(","); //$NON-NLS-1$
				for (String tag : tags) {
					programArguments.add("--exclude-tag"); //$NON-NLS-1$
					programArguments.add(tag.trim());
				}
			}
		}

		if (addOpensTargets != null) {
			vmArguments.addAll(addOpensVmArgs);
		}
	}

	private static boolean isOnModulePath(IJavaProject javaProject, String typeToCheck) {
		try {
			IType type = javaProject.findType(typeToCheck);
			if (type == null) {
				return false;
			}
			IPackageFragmentRoot packageFragmentRoot = (IPackageFragmentRoot) type.getPackageFragment().getParent();
			IClasspathEntry resolvedClasspathEntry = packageFragmentRoot.getResolvedClasspathEntry();
			return Arrays.stream(resolvedClasspathEntry.getExtraAttributes())
					.anyMatch(p -> p.getName().equals(IClasspathAttribute.MODULE) && p.getValue().equals("true")); //$NON-NLS-1$
		} catch (JavaModelException e) {
			// if anything goes wrong, assume true (in the worst case, user get a warning
			// because of a redundant add-opens)
			return true;
		}
	}

	private void collectAddOpensVmArgs(String addOpensTargets, List<String> addOpensVmArgs, IJavaElement javaElem,
			ILaunchConfiguration configuration) throws CoreException {
		if (addOpensTargets != null) {
			IPackageFragment pkg = getParentPackageFragment(javaElem);
			if (pkg != null) {
				String pkgName = pkg.getElementName();
				collectAddOpensVmArgs(addOpensTargets, addOpensVmArgs, pkgName, configuration);
			}
		}
	}

	private void collectAddOpensVmArgs(String addOpensTargets, List<String> addOpensVmArgs, String pkgName,
			ILaunchConfiguration configuration) throws CoreException {
		if (addOpensTargets != null) {
			IJavaProject javaProject = getJavaProject(configuration);
			String sourceModuleName = javaProject.getModuleDescription().getElementName();
			addOpensVmArgs.add("--add-opens"); //$NON-NLS-1$
			addOpensVmArgs.add(sourceModuleName + "/" + pkgName + "=" + addOpensTargets); //$NON-NLS-1$ //$NON-NLS-2$
		}
	}

	private IPackageFragment getParentPackageFragment(IJavaElement element) {
		IJavaElement parent = element.getParent();
		while (parent != null) {
			if (parent instanceof IPackageFragment) {
				return (IPackageFragment) parent;
			}
			parent = parent.getParent();
		}
		return null;
	}

	private String createPackageNamesFile(IJavaElement testContainer, ITestKind testRunnerKind, Set<String> pkgNames)
			throws CoreException {
		try {
			File file = File.createTempFile("packageNames", ".txt"); //$NON-NLS-1$ //$NON-NLS-2$
			file.deleteOnExit();

			try (BufferedWriter bw = new BufferedWriter(
					new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8))) {
				if (testContainer instanceof IPackageFragment) {
					pkgNames.add(getPackageName(testContainer.getElementName()));
				} else if (testContainer instanceof IPackageFragmentRoot) {
					addAllPackageFragments((IPackageFragmentRoot) testContainer, pkgNames);
				} else if (testContainer instanceof IJavaProject) {
					for (IPackageFragmentRoot pkgFragmentRoot : ((IJavaProject) testContainer)
							.getPackageFragmentRoots()) {
						if (!pkgFragmentRoot.isExternal() && !pkgFragmentRoot.isArchive()) {
							addAllPackageFragments(pkgFragmentRoot, pkgNames);
						}
					}
				} else {
					abort(Messages.JUnitPluginLaunchConfigurationDelegate_error_wrong_input, null,
							IJavaLaunchConfigurationConstants.ERR_UNSPECIFIED_MAIN_TYPE);
				}
				if (pkgNames.isEmpty()) {
					String msg = MessageFormat.format(
							Messages.JUnitPluginLaunchConfigurationDelegate_error_notests_kind,
							testRunnerKind.getDisplayName());
					abort(msg, null, IJavaLaunchConfigurationConstants.ERR_UNSPECIFIED_MAIN_TYPE);
				} else {
					for (String pkgName : pkgNames) {
						bw.write(pkgName);
						bw.newLine();
					}
				}
			}
			return file.getAbsolutePath();
		} catch (IOException | JavaModelException e) {
			throw new CoreException(Status.error("", e)); //$NON-NLS-1$
		}
	}

	private Set<String> addAllPackageFragments(IPackageFragmentRoot pkgFragmentRoot, Set<String> pkgNames)
			throws JavaModelException {
		for (IJavaElement child : pkgFragmentRoot.getChildren()) {
			if (child instanceof IPackageFragment && ((IPackageFragment) child).hasChildren()) {
				pkgNames.add(getPackageName(child.getElementName()));
			}
		}
		return pkgNames;
	}

	private String getPackageName(String elementName) {
		if (elementName.isEmpty()) {
			return DEFAULT;
		}
		return elementName;
	}

	private String createTestNamesFile(IJavaElement[] testElements) throws CoreException {
		try {
			File file = File.createTempFile("testNames", ".txt"); //$NON-NLS-1$ //$NON-NLS-2$
			file.deleteOnExit();
			try (BufferedWriter bw = new BufferedWriter(
					new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8));) {
				for (IJavaElement testElement : testElements) {
					if (testElement instanceof IType type) {
						String testName = type.getFullyQualifiedName();
						bw.write(testName);
						bw.newLine();
					} else {
						abort(Messages.JUnitPluginLaunchConfigurationDelegate_error_wrong_input, null,
								IJavaLaunchConfigurationConstants.ERR_UNSPECIFIED_MAIN_TYPE);
					}
				}
			}
			return file.getAbsolutePath();
		} catch (IOException e) {
			throw new CoreException(Status.error("", e)); //$NON-NLS-1$
		}
	}

	@Override
	public String[][] getClasspathAndModulepath(ILaunchConfiguration configuration) throws CoreException {
		String[] classpath = LaunchArgumentsHelper.constructClasspath(configuration);
		if (classpath == null) {
			abort(Messages.JUnitPluginLaunchConfigurationDelegate_error_noStartup, null, IStatus.OK);
		}
		String[][] cpmp = internalGetClasspathAndModulepath(configuration);
		cpmp[0] = classpath;
		return cpmp;
	}

	public String[][] internalGetClasspathAndModulepath(ILaunchConfiguration configuration) throws CoreException {
		String[][] cpmp = super.getClasspathAndModulepath(configuration);
		String[] cp = cpmp[0];

		List<String> junitEntries = new ClasspathLocalizer(Platform.inDevelopmentMode())
				.localizeClasspath(getJUnitVersion(configuration));

		String[] classPath = new String[cp.length + junitEntries.size()];
		Object[] jea = junitEntries.toArray();
		System.arraycopy(cp, 0, classPath, 0, cp.length);
		System.arraycopy(jea, 0, classPath, cp.length, jea.length);

		cpmp[0] = classPath;

		return cpmp;
	}

	private static class ClasspathLocalizer {

		private final boolean fInDevelopmentMode;

		public ClasspathLocalizer(boolean inDevelopmentMode) {
			fInDevelopmentMode = inDevelopmentMode;
		}

		public List<String> localizeClasspath(JUnitVersion junitVersion) {
			JUnitRuntimeClasspathEntry[] entries = junitVersion.getJUnitTestKind().getClasspathEntries();
			List<String> junitEntries = new ArrayList<>();

			for (JUnitRuntimeClasspathEntry entrie : entries) {
				try {
					addEntry(junitEntries, entrie);
				} catch (IOException | URISyntaxException e) {
					Assert.isTrue(false, entrie.getPluginId() + " is available (required JAR)"); //$NON-NLS-1$
				}
			}
			return junitEntries;
		}

		private void addEntry(List<String> junitEntries, final JUnitRuntimeClasspathEntry entry)
				throws IOException, MalformedURLException, URISyntaxException {
			String entryString = entryString(entry);
			if (entryString != null) {
				junitEntries.add(entryString);
			}
		}

		private String entryString(final JUnitRuntimeClasspathEntry entry)
				throws IOException, MalformedURLException, URISyntaxException {
			if (inDevelopmentMode()) {
				try {
					return localURL(entry.developmentModeEntry());
				} catch (IOException e3) {
					// fall through and try default
				}
			}
			return localURL(entry);
		}

		private boolean inDevelopmentMode() {
			return fInDevelopmentMode;
		}

		private String localURL(JUnitRuntimeClasspathEntry jar)
				throws IOException, MalformedURLException, URISyntaxException {
			Bundle bundle = JUnitPluginTestPlugin.getDefault().getBundle(jar.getPluginId());
			URL url;
			if (jar.getPluginRelativePath() == null) {
				String bundleClassPath = bundle.getHeaders().get(Constants.BUNDLE_CLASSPATH);
				url = bundleClassPath != null ? bundle.getEntry(bundleClassPath) : null;
				if (url == null) {
					url = bundle.getEntry("/"); //$NON-NLS-1$
				}
			} else {
				url = bundle.getEntry(jar.getPluginRelativePath());
			}

			if (url == null) {
				throw new IOException();
			}
			return URIUtil.toFile(URIUtil.toURI(FileLocator.toFileURL(url))).getAbsolutePath(); // See bug 503050
		}
	}

	private final IJavaElement getTestTarget(ILaunchConfiguration configuration, IJavaProject javaProject)
			throws CoreException {
		String containerHandle = configuration.getAttribute(JUnitLaunchConfigurationConstants.ATTR_TEST_CONTAINER, ""); //$NON-NLS-1$
		if (containerHandle.length() != 0) {
			IJavaElement element = JavaCore.create(containerHandle);
			if (element == null || !element.exists()) {
				abort(Messages.JUnitPluginLaunchConfigurationDelegate_error_input_element_deosn_not_exist, null,
						IJavaLaunchConfigurationConstants.ERR_UNSPECIFIED_MAIN_TYPE);
			}
			return element;
		}
		String testTypeName = getMainTypeName(configuration);
		if (testTypeName != null && testTypeName.length() != 0) {
			IType type = javaProject.findType(testTypeName);
			if (type != null && type.exists()) {
				return type;
			}
		}
		abort(Messages.JUnitPluginLaunchConfigurationDelegate_input_type_does_not_exist, null,
				IJavaLaunchConfigurationConstants.ERR_UNSPECIFIED_MAIN_TYPE);
		return null; // not reachable
	}

	@Override
	protected void abort(String message, Throwable exception, int code) throws CoreException {
		throw new CoreException(new Status(IStatus.ERROR, JUnitPluginTestPlugin.PLUGIN_ID, code, message, exception));
	}

	// PDE JUnit delegate
	/**
	 * To avoid duplicating variable substitution (and duplicate prompts) this
	 * variable will store the substituted workspace location.
	 */
	private String fWorkspaceLocation;

	/**
	 * Caches the configuration directory when a launch is started
	 */
	protected File fConfigDir = null;

	// used to generate the dev classpath entries
	// key is bundle ID, value is a model
	private Map<String, List<IPluginModelBase>> fAllBundles;

	// key is a model, value is startLevel:autoStart
	private Map<IPluginModelBase, String> fModels;
	private String launchMode;

	private static final String PDE_JUNIT_SHOW_COMMAND = "pde.junit.showcommandline"; //$NON-NLS-1$

	@Override
	public IVMRunner getVMRunner(ILaunchConfiguration configuration, String mode) throws CoreException {
		IVMInstall launcher = VMHelper.createLauncher(configuration, fModels.keySet());
		return launcher.getVMRunner(mode);
	}

	private String getTestPluginId(ILaunchConfiguration configuration) throws CoreException {
		IJavaProject javaProject = getJavaProject(configuration);
		IPluginModelBase model = PluginRegistry.findModel(javaProject.getProject());
		if (model == null) {
			abort(NLS.bind(Messages.JUnitPluginLaunchConfigurationDelegate_error_notaplugin,
					javaProject.getProject().getName()), null, IStatus.OK);
			return null;
		}
		if (model instanceof IFragmentModel) {
			return ((IFragmentModel) model).getFragment().getPluginId();
		}

		return model.getPluginBase().getId();
	}

	@Override
	public String getModuleCLIOptions(ILaunchConfiguration configuration) throws CoreException {
		// The JVM options should be specified in target platform, see getVMArguments()
		return ""; //$NON-NLS-1$
	}

	/**
	 * Returns the application to launch plug-in tests with
	 *
	 * @since 3.5
	 *
	 * @param configuration The launch configuration in which the application is
	 *                      specified.
	 * @return the application
	 */
	protected String getApplication(ILaunchConfiguration configuration) {
		String application = null;

		boolean shouldRunInUIThread = true;
		try {
			shouldRunInUIThread = configuration.getAttribute(IPDELauncherConstants.RUN_IN_UI_THREAD, true);
		} catch (CoreException e) {
			// Ignore
		}

		if (!shouldRunInUIThread) {
			return IPDEConstants.NON_UI_THREAD_APPLICATION;
		}

		try {
			// if application is set, it must be a headless app.
			application = configuration.getAttribute(IPDELauncherConstants.APPLICATION, (String) null);
		} catch (CoreException e) {
			// Ignore
		}

		// launch the UI test application
		if (application == null) {
			application = IPDEConstants.UI_TEST_APPLICATION;
		}
		return application;
	}

	private IPluginModelBase findRequiredPluginInTargetOrHost(String id) throws CoreException {
		IPluginModelBase model = PluginRegistry.findModel(id);
		if (model == null || !model.getBundleDescription().isResolved()) {
			// prefer bundle from host over unresolved bundle from target
			model = PDECore.getDefault().findPluginInHost(id);
		}
		if (model == null) {
			abort(NLS.bind(Messages.JUnitPluginLaunchConfigurationDelegate_error_missingPlugin, id), null, IStatus.OK);
		}
		return model;
	}

	@Override
	public String getProgramArguments(ILaunchConfiguration configuration) throws CoreException {
		return LaunchArgumentsHelper.getUserProgramArguments(configuration);
	}

	@Override
	public String getVMArguments(ILaunchConfiguration configuration) throws CoreException {
		String vmArgs = LaunchArgumentsHelper.getUserVMArguments(configuration);

		// necessary for PDE to know how to load plugins when target platform = host
		// platform
		vmArgs = concatArg(vmArgs, "-Declipse.pde.launch=true"); //$NON-NLS-1$
		// For p2 target, add "-Declipse.p2.data.area=@config.dir/p2" unless already
		// specified by user
		if (fAllBundles.containsKey("org.eclipse.equinox.p2.core")) { //$NON-NLS-1$
			if (!vmArgs.contains("-Declipse.p2.data.area=")) { //$NON-NLS-1$
				vmArgs = concatArg(vmArgs, "-Declipse.p2.data.area=@config.dir" + File.separator + "p2"); //$NON-NLS-1$ //$NON-NLS-2$
			}
		}
		return vmArgs;
	}

	/**
	 * Returns the result of concatenating the given argument to the specified
	 * vmArgs.
	 *
	 * @param vmArgs existing VM arguments
	 * @param arg    argument to concatenate
	 * @return result of concatenation
	 */
	private String concatArg(String vmArgs, String arg) {
		if (vmArgs.length() > 0 && !vmArgs.endsWith(" ")) { //$NON-NLS-1$
			vmArgs = vmArgs.concat(" "); //$NON-NLS-1$
		}
		return vmArgs.concat(arg);
	}

	@Override
	public String[] getEnvironment(ILaunchConfiguration configuration) throws CoreException {
		return DebugPlugin.getDefault().getLaunchManager().getEnvironment(configuration);
	}

	@Deprecated
	@Override
	public String[] getClasspath(ILaunchConfiguration configuration) throws CoreException {
		String[] classpath = LaunchArgumentsHelper.constructClasspath(configuration);
		if (classpath == null) {
			abort(Messages.JUnitPluginLaunchConfigurationDelegate_error_noStartup, null, IStatus.OK);
		}
		return classpath;
	}

	@Override
	public File getWorkingDirectory(ILaunchConfiguration configuration) throws CoreException {
		return LaunchArgumentsHelper.getWorkingDirectory(configuration);
	}

	@Override
	public Map<String, Object> getVMSpecificAttributesMap(ILaunchConfiguration configuration) throws CoreException {
		return LaunchArgumentsHelper.getVMSpecificAttributesMap(configuration, fModels.keySet());
	}

	@Override
	protected void setDefaultSourceLocator(ILaunch launch, ILaunchConfiguration configuration) throws CoreException {
		ILaunchConfigurationWorkingCopy wc = null;
		if (configuration.isWorkingCopy()) {
			wc = (ILaunchConfigurationWorkingCopy) configuration;
		} else {
			wc = configuration.getWorkingCopy();
		}
		String id = configuration.getAttribute(IJavaLaunchConfigurationConstants.ATTR_SOURCE_PATH_PROVIDER,
				(String) null);
		if (!PDESourcePathProvider.ID.equals(id)) {
			wc.setAttribute(IJavaLaunchConfigurationConstants.ATTR_SOURCE_PATH_PROVIDER, PDESourcePathProvider.ID);
			wc.doSave();
		}

		manageLaunch(launch);
	}

	/**
	 * Returns the location of the configuration area
	 *
	 * @param configuration the launch configuration
	 * @return a directory where the configuration area is located
	 */
	protected File getConfigurationDirectory(ILaunchConfiguration configuration) {
		if (fConfigDir == null) {
			fConfigDir = LaunchConfigurationHelper.getConfigurationArea(configuration);
		}
		return fConfigDir;
	}

	@Override
	protected IProject[] getBuildOrder(ILaunchConfiguration configuration, String mode) throws CoreException {
		return computeBuildOrder(LaunchPluginValidator.getAffectedProjects(configuration));
	}

	@Override
	protected IProject[] getProjectsForProblemSearch(ILaunchConfiguration configuration, String mode)
			throws CoreException {
		return LaunchPluginValidator.getAffectedProjects(configuration);
	}

	/**
	 * Adds a listener to the launch to be notified at interesting launch lifecycle
	 * events such as when the launch terminates.
	 *
	 * @param launch the launch
	 */
	protected void manageLaunch(ILaunch launch) {
//		PDELaunchingPlugin.getDefault().getLaunchListener().manage(launch);
	}

	/**
	 * Checks for old-style plugin.xml files that have become stale since the last
	 * launch. For any stale plugin.xml files found, the corresponding MANIFEST.MF
	 * is deleted from the runtime configuration area so that it gets regenerated
	 * upon startup.
	 *
	 * @param configuration the launch configuration
	 * @param monitor       the progress monitor
	 */
	protected void synchronizeManifests(ILaunchConfiguration configuration, IProgressMonitor monitor) {
		LaunchConfigurationHelper.synchronizeManifests(configuration, getConfigurationDirectory(configuration));
		monitor.done();
	}

	/**
	 * Clears the workspace prior to launching if the workspace exists and the
	 * option to clear it is turned on. Also clears the configuration area if that
	 * option is chosen.
	 *
	 * @param configuration the launch configuration
	 * @param monitor       the progress monitor
	 * @throws CoreException if unable to retrieve launch attribute values
	 * @since 3.3
	 */
	protected void clear(ILaunchConfiguration configuration, IProgressMonitor monitor) throws CoreException {
		if (fWorkspaceLocation == null) {
			fWorkspaceLocation = LaunchArgumentsHelper.getWorkspaceLocation(configuration);
		}

		SubMonitor subMon = SubMonitor.convert(monitor, 50);

		// Clear workspace and prompt, if necessary
		LauncherUtils.clearWorkspace(configuration, fWorkspaceLocation, launchMode, subMon.split(25));

		subMon.setWorkRemaining(25);

		// clear config area, if necessary
		if (configuration.getAttribute(IPDELauncherConstants.CONFIG_CLEAR_AREA, false)) {
			CoreUtility.deleteContent(getConfigurationDirectory(configuration), subMon.split(25));
		}

		subMon.done();
	}

	/**
	 * Checks if the Automated Management of Dependencies option is turned on. If
	 * so, it makes aure all manifests are updated with the correct dependencies.
	 *
	 * @param configuration the launch configuration
	 * @param monitor       a progress monitor
	 */
	protected void validateProjectDependencies(ILaunchConfiguration configuration, IProgressMonitor monitor) {
		LauncherUtils.validateProjectDependencies(configuration, monitor);
	}

	/**
	 * Validates inter-bundle dependencies automatically prior to launching if that
	 * option is turned on.
	 *
	 * @param configuration the launch configuration
	 * @param monitor       a progress monitor
	 * @throws CoreException if unable to validate the dependencies
	 */
	protected void validatePluginDependencies(ILaunchConfiguration configuration, IProgressMonitor monitor)
			throws CoreException {
		EclipsePluginValidationOperation op = new EclipsePluginValidationOperation(configuration, fModels.keySet(),
				launchMode);
		LaunchPluginValidator.runValidationOperation(op, monitor);
	}
}
