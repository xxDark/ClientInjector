package net.minecraft.launchwrapper;

import cpw.mods.fml.common.asm.transformers.AccessTransformer;
import joptsimple.OptionParser;
import joptsimple.OptionSet;
import joptsimple.OptionSpec;

import java.io.*;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.*;
import java.util.jar.*;
import java.util.zip.ZipException;

import org.apache.logging.log4j.Level;

public class Launch {
    public static File minecraftHome;
    public static File assetsDir;
    public static Map<String, Object> blackboard;
    public static LaunchClassLoader classLoader;

    public static void main(String[] args) throws IOException {
        new Launch().launch(args);
    }

    private void launch(String[] args) throws IOException {
        final OptionParser parser = new OptionParser();
        parser.allowsUnrecognizedOptions();

        final OptionSpec<String> profileOption = parser.accepts("version", "The version we launched with").withRequiredArg();
        final OptionSpec<File> gameDirOption = parser.accepts("gameDir", "Alternative game directory").withRequiredArg().ofType(File.class);
        final OptionSpec<File> assetsDirOption = parser.accepts("assetsDir", "Assets directory").withRequiredArg().ofType(File.class);
        final OptionSpec<String> tweakClassOption = parser.accepts("tweakClass", "Tweak class(es) to load").withRequiredArg().required();

        final OptionSpec<File> inputOption = parser.accepts("inputJar", "Input game jar").withRequiredArg().ofType(File.class).required();
        final OptionSpec<Boolean> fmlOption = parser.accepts("fml").withRequiredArg().ofType(Boolean.TYPE).defaultsTo(Boolean.FALSE);
        final OptionSpec<File> outputOption = parser.accepts("outputDir", "Output directory").withRequiredArg().ofType(File.class).defaultsTo(new File("output"));

        final OptionSpec<String> nonOption = parser.nonOptions();

        final OptionSet options = parser.parse(args);
        final File inputFile = options.valueOf(inputOption);
        if (!inputFile.isFile()) {
            LogWrapper.log(Level.ERROR, "Input file does not exist!");
            System.exit(1);
        }
        final File tempDir = new File("tmp");
        if (!clean(tempDir)) {
            LogWrapper.warning("Error cleaning temp directory");
        }
        final File outputDir = options.valueOf(outputOption);
        if (!clean(outputDir) && !outputDir.mkdirs()) {
            LogWrapper.log(Level.FATAL, "Error cleaning output directory");
            System.exit(1);
        }

        final LaunchClassLoader classLoader = Launch.classLoader = new LaunchClassLoader(((URLClassLoader) Launch.class.getClassLoader()).getURLs());
        blackboard = new HashMap<String, Object>();
        Thread.currentThread().setContextClassLoader(classLoader);
        classLoader.addClassLoaderExclusion("jdk.");

        final URL inputURL = inputFile.toURI().toURL();
        classLoader.addURL(inputURL);
        final Map<String, JarOutputStream> outputs = new HashMap<>();
        try {
            final boolean fml = options.valueOf(fmlOption);
            minecraftHome = options.valueOf(gameDirOption);
            assetsDir = options.valueOf(assetsDirOption);
            final String profileName = options.valueOf(profileOption);
            final List<String> tweakClassNames = new ArrayList<String>(options.valuesOf(tweakClassOption));

            final List<String> argumentList = new ArrayList<String>();
            // This list of names will be interacted with through tweakers. They can append to this list
            // any 'discovered' tweakers from their preferred mod loading mechanism
            // By making this object discoverable and accessible it's possible to perform
            // things like cascading of tweakers
            blackboard.put("TweakClasses", tweakClassNames);

            // This argument list will be constructed from all tweakers. It is visible here so
            // all tweakers can figure out if a particular argument is present, and add it if not
            blackboard.put("ArgumentList", argumentList);

            if (!fml) {
                for (final URL url : classLoader.getURLs()) {
                    final String protocol = url.getProtocol();
                    if ("file".equals(protocol)) {
                        final File file;
                        try {
                            file = new File(url.toURI());
                        } catch (URISyntaxException ex) {
                            LogWrapper.log(Level.WARN, ex, "Broken URL: %s", url);
                            continue;
                        }
                        if (!file.isFile()) {
                            continue;
                        }

                        try (final JarFile jarFile = new JarFile(file)) {
                            final Manifest manifest = jarFile.getManifest();
                            if (manifest == null) {
                                continue;
                            }
                            final Attributes attributes = manifest.getMainAttributes();
                            if (attributes == null) {
                                continue;
                            }
                            final String fmlat = attributes.getValue("FMLAT");
                            if (fmlat == null) {
                                continue;
                            }
                            LogWrapper.info("Found FMLAT: %s", fmlat);
                            classLoader.registerTransformer(new AccessTransformer(jarFile, fmlat));
                        } catch (ZipException ex) {
                            LogWrapper.log(Level.FATAL, ex, "Unable to open jar: %s", file);
                            System.exit(1);
                        }
                    }
                }
            }

            // This is to prevent duplicates - in case a tweaker decides to add itself or something
            final Set<String> allTweakerNames = new HashSet<String>();
            // The 'definitive' list of tweakers
            final List<ITweaker> allTweakers = new ArrayList<ITweaker>();
            try {
                final List<ITweaker> tweakers = new ArrayList<ITweaker>(tweakClassNames.size() + 1);
                // The list of tweak instances - may be useful for interoperability
                blackboard.put("Tweaks", tweakers);
                // The primary tweaker (the first one specified on the command line) will actually
                // be responsible for providing the 'main' name and generally gets called first
                ITweaker primaryTweaker = null;
                // This loop will terminate, unless there is some sort of pathological tweaker
                // that reinserts itself with a new identity every pass
                // It is here to allow tweakers to "push" new tweak classes onto the 'stack' of
                // tweakers to evaluate allowing for cascaded discovery and injection of tweakers
                do {
                    for (final Iterator<String> it = tweakClassNames.iterator(); it.hasNext(); ) {
                        final String tweakName = it.next();
                        // Safety check - don't reprocess something we've already visited
                        if (allTweakerNames.contains(tweakName)) {
                            LogWrapper.log(Level.WARN, "Tweak class name %s has already been visited -- skipping", tweakName);
                            // remove the tweaker from the stack otherwise it will create an infinite loop
                            it.remove();
                            continue;
                        } else {
                            allTweakerNames.add(tweakName);
                        }
                        LogWrapper.log(Level.INFO, "Loading tweak class name %s", tweakName);

                        // Ensure we allow the tweak class to load with the parent classloader
                        classLoader.addClassLoaderExclusion(tweakName.substring(0, tweakName.lastIndexOf('.')));
                        final ITweaker tweaker = (ITweaker) Class.forName(tweakName, true, classLoader).newInstance();
                        tweakers.add(tweaker);

                        // Remove the tweaker from the list of tweaker names we've processed this pass
                        it.remove();
                        // If we haven't visited a tweaker yet, the first will become the 'primary' tweaker
                        if (primaryTweaker == null) {
                            LogWrapper.log(Level.INFO, "Using primary tweak class name %s", tweakName);
                            primaryTweaker = tweaker;
                        }
                    }

                    // Now, iterate all the tweakers we just instantiated
                    for (final Iterator<ITweaker> it = tweakers.iterator(); it.hasNext(); ) {
                        final ITweaker tweaker = it.next();
                        LogWrapper.log(Level.INFO, "Calling tweak class %s", tweaker.getClass().getName());
                        tweaker.acceptOptions(options.valuesOf(nonOption), minecraftHome, assetsDir, profileName);
                        tweaker.injectIntoClassLoader(classLoader);
                        allTweakers.add(tweaker);
                        // again, remove from the list once we've processed it, so we don't get duplicates
                        it.remove();
                    }
                    // continue around the loop until there's no tweak classes
                } while (!tweakClassNames.isEmpty());

                // Once we're done, we then ask all the tweakers for their arguments and add them all to the
                // master argument list
                for (final ITweaker tweaker : allTweakers) {
                    argumentList.addAll(Arrays.asList(tweaker.getLaunchArguments()));
                }

                // Finally we turn to the primary tweaker, and let it tell us where to go to launch
                final String launchTarget = primaryTweaker.getLaunchTarget();

                final Map<JarOutputStream, Set<String>> entriesMap = new HashMap<>();
                final byte[] buffer = new byte[16384];
                final ByteArrayOutputStream baos = new ByteArrayOutputStream(16384);
                classLoader.classDump = (originalName, finalName, bytes, clazz) -> {
                    final URL location = clazz.getProtectionDomain().getCodeSource().getLocation();
                    final JarOutputStream outputJar;
                    try {
                        outputJar = getJar(outputDir, outputs, location);
                    } catch (URISyntaxException e) {
                        e.printStackTrace();
                        LogWrapper.log(Level.FATAL, e, null);
                        System.exit(1);
                        return;
                    }
                    final String entryName = finalName.replace('.', '/') + ".class";
                    if (entriesMap.computeIfAbsent(outputJar, __ -> new HashSet<>(512)).add(entryName)) {
                        LogWrapper.info("Dumping class: %s/%d", finalName, outputJar.hashCode());
                        outputJar.putNextEntry(new JarEntry(entryName));
                        outputJar.write(bytes);
                        outputJar.closeEntry();
                    }
                };
                // Let's rock!
                LogWrapper.info("Starting");
                if (launchTarget != null) {
                    LogWrapper.info("Launch target was set to %s, so we will try to load  it first.", launchTarget);
                    classLoader.loadClass(launchTarget, true);
                }

                final URL[] classpath = classLoader.getURLs();
                final Set<URL> visited = new HashSet<>(classpath.length, 1F);
                // First visit minecraft.jar
                visitJar(entriesMap, inputURL, baos, buffer, outputDir, outputs);
                visited.add(inputURL);
                visited.add(new File(Launch.class.getProtectionDomain().getCodeSource().getLocation().toURI().getPath()).toURI().toURL());

                for (final URL url : classLoader.getURLs()) {
                    if (visited.add(url)) {
                        visitJar(entriesMap, url, baos, buffer, outputDir, outputs);
                    }
                }

                LogWrapper.info("Done transforming jar files");
            } catch (Exception e) {
                LogWrapper.log(Level.ERROR, e, "Unable to launch");
                System.exit(1);
            }
        } finally {
            // Must close
            for (final JarOutputStream jar : outputs.values()) {
                jar.flush();
                jar.close();
            }
        }
    }

    private static void visitJar(final Map<JarOutputStream, Set<String>> entries, final URL url, final ByteArrayOutputStream baos, final byte[] buffer, final File outputDir, final Map<String, JarOutputStream> outputs) throws URISyntaxException, IOException, ClassNotFoundException {
        try (final JarFile inputJar = new JarFile(new File(url.toURI()))) {
            LogWrapper.info("Visiting: %s", url);
            final Enumeration<JarEntry> enumeration = inputJar.entries();
            while (enumeration.hasMoreElements()) {
                final JarEntry entry = enumeration.nextElement();
                final String name = entry.getName();
                if (!name.endsWith(".class")) {
                    final JarOutputStream outputJar = getJar(outputDir, outputs, url);
                    if (entries.computeIfAbsent(outputJar, __ -> new HashSet<>(512)).add(name)) {
                        baos.reset();
                        try (final InputStream in = inputJar.getInputStream(entry)) {
                            int r;
                            while ((r = in.read(buffer)) != -1) {
                                baos.write(buffer, 0, r);
                            }
                        }
                        final byte[] bytes = baos.toByteArray();
                        outputJar.putNextEntry(new JarEntry(name));
                        outputJar.write(bytes);
                        outputJar.closeEntry();
                    }
                } else {
                    classLoader.loadClass(name.substring(0, name.length() - 6).replace('/', '.'), true);
                }
            }
        }
    }

    private static JarOutputStream getJar(final File outputDir, final Map<String, JarOutputStream> cache, final URL file) throws URISyntaxException {
        return cache.computeIfAbsent(toAbsolutePath(file), url -> {
            try {
                final File ref = new File(outputDir, new File(url).getName());
                if (ref.delete()) {
                    ref.createNewFile();
                }
                final JarOutputStream jar = new JarOutputStream(new FileOutputStream(ref));
                LogWrapper.info("Stored new jar: %s ; %s ; %s", url, ref, jar.hashCode());
                return jar;
            } catch (IOException ex) {
                throw new RuntimeException(ex);
            }
        });
    }

    private static String toAbsolutePath(final URL url) {
         String path = url.getPath();
        final int protocolIndex = path.indexOf("file:");
        if (protocolIndex != -1) {
            path = path.substring(protocolIndex + 5);
        }
        final int index = path.lastIndexOf('!');
        return index == -1 ? path : path.substring(0, index);
    }

    private static boolean clean(final File dir) {
        final File[] files = dir.listFiles();
        if (files != null) {
            for (final File file : files) {
                if (file.isFile()) {
                    if (!file.delete()) {
                        return false;
                    }
                } else if (!clean(dir)) {
                    return false;
                }
            }
        }
        return true;
    }
}
