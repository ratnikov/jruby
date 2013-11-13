package org.jruby.util;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.FileNotFoundException;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarInputStream;
import static org.jruby.util.URLUtil.getPath;

class CompoundJarHandler {
  public static InputStream openCompoundStream(URL base, String... path) throws IOException {
    StringBuilder pathBuilder = new StringBuilder();

    if (base.getProtocol().equals("jar")) {
      pathBuilder.append(getPath(base));
    } else {
      pathBuilder.append(base.toExternalForm());
    }

    for (String entry : path) {
      pathBuilder.append("!");

      if (!entry.startsWith("/")) {
        pathBuilder.append("/");
      }

      pathBuilder.append(entry);
    }

    return new CompoundJarHandler(pathBuilder.toString()).getInputStream();
  }

  private final URL baseJarUrl;

  private final String resourcePath;
  private final String[] path;

  private static final Map<String, Map<String, byte[]>> cache = new ConcurrentHashMap<String, Map<String, byte[]>>(16, 0.75f, 4);

  CompoundJarHandler(String resourcePath) throws MalformedURLException {
    this.resourcePath = resourcePath;
    path = resourcePath.split("\\!\\/");
    baseJarUrl = new URL(path[0]);
  }

  private InputStream openEntryWithCache(String[] path, InputStream currentStream, int currentDepth) throws IOException {
    final String localPath = path[currentDepth];

    // short-circuit files directly on the filesystem, which JarFile can handle
    if (currentDepth == 1 && path[0].indexOf('!') == -1 && path[0].startsWith("file:")) {
      // it's a top-level jar, just open with JarFile
      JarFile jarFile = new JarFile(path[0].substring(5));
      JarEntry entry = jarFile.getJarEntry(localPath);
      if (entry != null) {
        return returnOrRecurse(path, jarFile.getInputStream(entry), currentDepth);
      }
      return null;
    }

    // build path for cache lookup
    StringBuilder pathToHereBuffer = new StringBuilder();
    for (int i = 0; i < currentDepth; i++) {
      if (i > 0) pathToHereBuffer.append("!/");
      pathToHereBuffer.append(path[i]);
    }
    String pathToHere = pathToHereBuffer.toString();

    // check for already-read cached jar contents
    Map<String, byte[]> contents = cache.get(pathToHere);
    if (contents == null) {
      // not found, cache jar contents
      cache.put(pathToHere, contents = new ConcurrentHashMap<String, byte[]>(16, 0.75f, 2));
      cacheJarFrom(currentStream, contents);
    }

    // now go to cache to find bytes for this elements
    byte[] bytes = contents.get(localPath);
    if (bytes != null) {
      return returnOrRecurse(path, new ByteArrayInputStream(bytes), currentDepth);
    }

    return null;
  }

  private void cacheJarFrom(InputStream currentStream, Map<String, byte[]> contents) throws IOException {
    JarInputStream currentJar = new JarInputStream(currentStream);
    for (JarEntry entry = currentJar.getNextJarEntry(); entry != null; entry = currentJar.getNextJarEntry()) {
      ByteArrayOutputStream baos = new ByteArrayOutputStream();
      byte[] bytes = new byte[1024];
      int size;
      while ((size = currentJar.read(bytes, 0, 1024)) != -1) {
        baos.write(bytes, 0, size);
      }
      bytes = baos.toByteArray();
      contents.put(entry.getName(), bytes);
    }
  }

  private InputStream returnOrRecurse(String[] path, InputStream nextStream, int currentDepth) throws IOException {
    if (currentDepth + 1 < path.length) {
      // we're not all the way down, open the inner jar and go deeper
      return openEntryWithCache(path, nextStream, currentDepth + 1);
    } else {
      // we've got it; return an array reading the bytes
      return nextStream;
    }
  }

  private static void close(Closeable resource) {
    if (resource != null) {
      try {
        resource.close();
      } catch (IOException ignore) {
      }
    }
  }

  public InputStream getInputStream() throws IOException {

    InputStream result;

    InputStream baseInputStream = baseJarUrl.openStream();

    if (path.length > 1) {
      try {
        result = openEntryWithCache(path, baseInputStream, 1);
      } catch (IOException ex) {
        close(baseInputStream);

        throw ex;
      } catch (RuntimeException ex) {
        close(baseInputStream);

        throw ex;
      }
    } else {
      result = baseInputStream;
    }

    if (result == null) {
      throw new FileNotFoundException(resourcePath);
    }

    return result;
  }
}
