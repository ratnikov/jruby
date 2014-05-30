package org.jruby.runtime.load;

import org.jruby.Ruby;
import org.jruby.RubyArray;
import org.jruby.RubyHash;
import org.jruby.RubyString;
import org.jruby.runtime.builtin.IRubyObject;
import org.jruby.runtime.load.LoadService.SuffixType;
import org.jruby.util.FileResource;
import org.jruby.util.JRubyFile;
import org.jruby.exceptions.RaiseException;
import org.jruby.ast.executable.Script;
import java.io.*;
import java.net.*;
import java.util.regex.*;
import java.util.*;

class LibrarySearcher {
  static class Ruby18 extends LibrarySearcher {
    public Ruby18(LoadService loadService) {
      super(loadService);
    }

    @Override
    protected String resolveLoadName(FileResource unused, String ruby18Path) {
      return ruby18Path;
    }
  }

  static class FoundLibrary implements Library {
    private final Library delegate;
    private final String scriptName;

    public FoundLibrary(Library delegate, String scriptName) {
      this.delegate = delegate;
      this.scriptName = scriptName;
    }

    @Override
    public void load(Ruby runtime, boolean wrap) throws IOException {
      delegate.load(runtime, wrap);
    }

    public String getScriptName() {
      return scriptName;
    }
  }

  private final LoadService loadService;
  private final Ruby runtime;
  private final Map<String, Library> builtinLibraries;

  public LibrarySearcher(LoadService loadService) {
    this.loadService = loadService;
    this.runtime = loadService.runtime;
    this.builtinLibraries = loadService.builtinLibraries;
  }

  public FoundLibrary findBySearchState(LoadService.SearchState state) {
    FoundLibrary lib = findLibrary(state.searchFile, state.suffixType);
    if (lib != null) {
      state.library = lib;
      state.loadName = lib.getScriptName();
    }
    return lib;
  }

  public FoundLibrary findLibrary(String baseName, SuffixType suffixType) {
    FoundLibrary library = null;
    
    if (library == null) library = findBuiltinLibrary(baseName, suffixType);
    if (library == null) library = findResourceLibrary(baseName, suffixType);
    if (library == null) library = findServiceLibrary(baseName, suffixType);

    return library;
  }

  private FoundLibrary findBuiltinLibrary(String name, SuffixType suffixType) {
    for (String suffix : suffixType.getSuffixes()) {
      String namePlusSuffix = name + suffix;
      if (builtinLibraries.containsKey(namePlusSuffix)) {
        return new FoundLibrary(
            builtinLibraries.get(namePlusSuffix),
            namePlusSuffix);
      }
    }
    return null;
  }

  private FoundLibrary findServiceLibrary(String name, SuffixType ignored) {
    Library extensionLibrary = ClassExtensionLibrary.tryFind(runtime, name);
    return extensionLibrary != null ? new FoundLibrary(extensionLibrary, name) : null;
  }

  private FoundLibrary findResourceLibrary(String baseName, SuffixType suffixType) {
    if (baseName.startsWith("./")) {
      return findFileResource(baseName, suffixType);
    }

    if (baseName.startsWith("../")) {
      // Path should be canonicalized in the findFileResource
      return findFileResource(baseName, suffixType);
    }

    if (baseName.startsWith("~/")) {
      RubyHash env = (RubyHash) runtime.getObject().getConstant("ENV");
      RubyString env_home = runtime.newString("HOME");
      if (env.has_key_p(env_home).isFalse()) {
        return null;
      }
      String home = env.op_aref(runtime.getCurrentContext(), env_home).toString();
      String path = home + "/" + baseName.substring(2);

      return findFileResource(path, suffixType);
    }

    // If path is absolute, try loading it directly
    if (Pattern.matches("([^:]+:)*/.*", baseName)) {
      return findFileResource(baseName, suffixType);
    }

    // A hack because apparently test_load tests expect to be able to load file:foo.jar even if
    // '.' is not in $LOAD_PATH. *sigh*
    // This probably shouldn't survive into real release.
    if (baseName.startsWith("file:")) {
      String name = baseName.substring(5);
      FoundLibrary found = findFileResource(name, suffixType);
      if (found != null) {
        return found;
      }
    }

    for (IRubyObject loadPathEntry : loadService.loadPath.toJavaArray()) {
      String loadPathString = loadPathEntry.convertToString().asJavaString();
      FoundLibrary library = findFileResourceWithLoadPath(baseName, suffixType, loadPathString);
      if (library != null) {
        return library;
      }
    }

    return null;
  }

  private FoundLibrary findFileResource(String searchName, SuffixType suffixType) {
    return findFileResourceWithLoadPath(searchName, suffixType, null);
  }

  private FoundLibrary findFileResourceWithLoadPath(String searchName, SuffixType suffixType, String loadPath) {
    for (String suffix : suffixType.getSuffixes()) {
      String fullPath = loadPath != null ? loadPath + "/" + searchName : searchName;
      String pathWithSuffix = fullPath + suffix;

      FileResource resource = JRubyFile.createResource(runtime, pathWithSuffix);
      if (resource.exists()) {
        return new FoundLibrary(
            new ResourceLibrary(searchName, fullPath, resource, suffix),
            resolveLoadName(resource, searchName + suffix));
      }
    }

    return null;
  }

  protected String resolveLoadName(FileResource resource, String ruby18path) {
    return resource.absolutePath();
  }

  static class ResourceLibrary implements Library {
    private final String searchName;
    private final String ruby18File;
    private final String suffix;
    private final InputStream is;
    private final String location;

    public ResourceLibrary(String searchName, String ruby18File, FileResource resource, String suffix) {
      this.searchName = searchName;
      this.ruby18File = ruby18File;
      this.suffix = suffix;
      this.location = resource.absolutePath();

      // getInputStream may return a null to denote that it cannot really read the resource.
      // We should raise LoadError in the end, but probably only once we actually try to load
      // the library
      this.is = resource.getInputStream();
    }

    @Override
    public void load(Ruby runtime, boolean wrap) {
      if (is == null) {
        throw runtime.newLoadError("no such file to load -- " + searchName, searchName);
      }

      if (location.endsWith(".jar")) {
        loadJar(runtime, wrap);
      } else if (location.endsWith(".class")) {
        loadClass(runtime, wrap);
      } else {
        loadScript(runtime, wrap);
      }
    }

    protected String filename(Ruby runtime) {
      if (runtime.is1_9()) {
        return location;
      }

      return searchName.startsWith("./") ? searchName + suffix : ruby18File + suffix;
    }

    private void loadScript(Ruby runtime, boolean wrap) {
      runtime.loadFile(filename(runtime), is, wrap);
    }

    private void loadClass(Ruby runtime, boolean wrap) {
      Script script = CompiledScriptLoader.loadScriptFromFile(runtime, is, searchName);
      if (script == null) {
        // we're depending on the side effect of the load, which loads the class but does not turn it into a script
        // I don't like it, but until we restructure the code a bit more, we'll need to quietly let it by here.
        return;
      }
      script.setFilename(filename(runtime));
      runtime.loadScript(script, wrap);
    }

    private void loadJar(Ruby runtime, boolean wrap) {
      try {
        URL url = new File(location).toURI().toURL();
        runtime.getJRubyClassLoader().addURL(url);
      } catch (MalformedURLException badUrl) {
        runtime.newIOErrorFromException(badUrl);
      }

      // If an associated Service library exists, load it as well
      ClassExtensionLibrary serviceExtension = ClassExtensionLibrary.tryFind(runtime, searchName);
      if (serviceExtension != null) {
        serviceExtension.load(runtime, wrap);
      }
    }
  }
}
