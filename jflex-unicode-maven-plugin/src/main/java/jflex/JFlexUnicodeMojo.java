/* * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * *
 * JFlex Unicode Maven 2 plugin                                            *
 * Copyright (c) 2008 Steve Rowe <steve_rowe@users.sf.net>                 *
 *                                                                         *
 *                                                                         *
 * This program is free software; you can redistribute it and/or modify    *
 * it under the terms of the GNU General Public License. See the file      *
 * COPYRIGHT for more information.                                         *
 *                                                                         *
 * This program is distributed in the hope that it will be useful,         *
 * but WITHOUT ANY WARRANTY; without even the implied warranty of          *
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the           *
 * GNU General Public License for more details.                            *
 *                                                                         *
 * You should have received a copy of the GNU General Public License along *
 * with this program; if not, write to the Free Software Foundation, Inc., *
 * 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA                 *
 *                                                                         *
 * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * */

package jflex;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;

import java.net.URL;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.SortedMap;
import java.util.Collections;
import java.util.TreeMap;
import java.util.Date;
import java.util.EnumMap;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;


/**
 * Generates source code for JFlex Unicode character property handling.
 * 
 * @goal generate-unicode-properties
 * @phase generate-sources
 *
 */
public class JFlexUnicodeMojo extends AbstractMojo {

  /** Top-level directory URL from which to download Unicode data */
  private static final String UNICODE_DOT_ORG_URL
    = "http://www.unicode.org/Public/";

  /**
   * Pattern for Unicode version links in the page at
   * {@value #UNICODE_DOT_ORG_URL}
   */
  private static final Pattern UNICODE_VERSION_LINK_PATTERN = Pattern.compile
    ("<a href=\"((\\d+(?:\\.\\d+){1,2})(?i:-(Update(\\d*)))?/)\">");

  /** Buffer size to use when reading web page content */
  private static final int BUF_SIZE = 4096;

  /** The date, for use in comments on the output class. */
  private static final String date
    = (new SimpleDateFormat("yyyy-MM-dd")).format(new Date());

  /**
   * Name of the directory into which the code will be generated.
   *
   * @parameter expression="${basedir}/src/main/java/jflex/unicode"
   */
  private File outputDirectory = null;

  /**
   * Maps validated major.minor unicode versions to information about the
   * version.
   */
  private SortedMap<String,UnicodeVersion> unicodeVersions
    = new TreeMap<String,UnicodeVersion>();

  /** The name of the output file (without .java) and the contained class. */
  private static final String OUTPUT_CLASS_NAME = "UnicodeProperties";

  /** The name of the skeleton file for the output class. */
  private static final String SKELETON_FILENAME
    = OUTPUT_CLASS_NAME + ".java.skeleton";


  /**
   * <ol>
   *   <li>Collects and validates Unicode versions to support from
   *       <a href="http://www.unicode.org/Public/">Unicode.org</a>;
   *       finds the most recent updates for non-beta versions, so that
   *       JFlex scanner specs can specify major or major.minor Unicode
   *       versions as an optional parameter to the %unicode option.</li>
   *   <li>For each version:
   *     <ol type="a">
   *       <li>Downloads the following Unicode data files:
   *         <ul>
   *           <li>UnicodeData(-X.X.X).txt</li>
   *           <li>PropertyAliases(-X.X.X).txt</li>
   *           <li>PropertyValueAliases(-X.X.X).txt</li>
   *           <li>DerivedCoreProperties(-X.X.X).txt</li>
   *           <li>Scripts(-X.X.X).txt</li>
   *           <li>Blocks(-X.X.X).txt</li>
   *           <li>PropList(-X.X.X).txt</li>
   *         </ul>
   *       <li>Parses the data files, extracting ranges of code points
   *           and property values associated with them (see
   *           <a href="http://www.unicode.org/reports/tr23/">The Unicode
   *           Character Property Model</a>).</li>
   *     </ol>
   *   </li>
   *   <li>Generates Java source for class UnicodeProperties:
   *     <ol type="a">
   *       <li>Has a constructor taking in a string representing
   *           the Unicode version given as parameter to the %unicode
   *           JFlex specification option.</li>
   *       <li>Has a default constructor that defaults the version
   *           of Unicode to that supported by the JRE, for JFlex
   *           specifications that have parameter-less %unicode
   *           options.</li>
   *       <li>Has per-Unicode-version compressed tables for each
   *           property value, which will be unpacked by the
   *           constructor for the selected Unicode version.</li>
   *       <li>Binds the Unicode-version-specific unpacked tables
   *           to code point ranges for named property values.</li>
   *       <li>Binds property value aliases, e.g. \p{Letter} for \p{L} (which
   *           is [\p{Lu}\p{Ll}\p{Lt}\p{Lm}\p{Lo}] ) - see
   *           <a href="http://www.unicode.org/Public/UNIDATA/UCD.html#General_Category_Values">
   *           Unicode General Category Property Values</a>.</li>
   *       <li>Has Unicode-version-specific method maximumCodePoint():int.</li>
   *     </ol>
   *   </li>
   * </ol>
   */
  public void execute() throws MojoExecutionException, MojoFailureException {
    try {
      getLog().info("Downloading Unicode data from " + UNICODE_DOT_ORG_URL + "\n");
      collectUnicodeVersions();
      emitUnicodeProperties();
      emitVersionedUnicodeData();
    } catch (Exception e) {
      throw new MojoExecutionException("Exception", e);
    }
  }

  /**
   * Searches unicode.org for available Unicode versions, and collects all
   * supported property data for each version.
   *
   * @throws IOException If there is a problem fetching or parsing the data
   */
  private void collectUnicodeVersions() throws IOException {
    // Maps available versions to maps from update numbers to relative URLs.
    // A version with no update is given update number "-1" for the purposes
    // of comparison.
    SortedMap<String,SortedMap<Integer,String>> allUnicodeVersions
      = new TreeMap<String,SortedMap<Integer,String>>();

    URL unicodeURL = new URL(UNICODE_DOT_ORG_URL);
    Matcher matcher
      = UNICODE_VERSION_LINK_PATTERN.matcher(getPageContent(unicodeURL));
    while (matcher.find()) {
      String relativeURL = matcher.group(1);
      String baseVersion = matcher.group(2);
      String update = matcher.group(3);
      int updateNumber = -1;
      if (null != update) {
        updateNumber = 0;
        if (null != matcher.group(4) && matcher.group(4).length() > 0) {
          updateNumber = Integer.parseInt(matcher.group(4));
        }
      }
      SortedMap<Integer,String> updates = allUnicodeVersions.get(baseVersion);
      if (null == updates) {
        updates = new TreeMap<Integer,String>(Collections.reverseOrder());
        allUnicodeVersions.put(baseVersion, updates);
      }
      updates.put(updateNumber, relativeURL);
    }
    for (String version : allUnicodeVersions.keySet()) {
      populateUnicodeVersion(version, allUnicodeVersions.get(version));
    }
  }

  /**
   * Given a Unicode version identifier and a corresponding set of relative
   * URLs, one for each available update, populates properties for this Unicode
   * version.
   *
   * @param version The Unicode version, either in form "X.X.X" or "X.X"
   * @param relativeURLs A sorted map from update number to relative URL
   * @throws IOException If there is a problem fetching or parsing the data
   */
  private void populateUnicodeVersion(String version,
                                      SortedMap<Integer,String> relativeURLs)
    throws IOException {
    
    EnumMap<DataFileType,URL> dataFiles 
      = new EnumMap<DataFileType,URL>(DataFileType.class);

    // The relative URLs are sorted in reverse order of update number; as a
    // result, the most recent update is first, the next most recent is next,
    // etc.  The first relative URL with a non-beta UnicodeData-X.X.X.txt
    // will be used.
    for (String relativeURL : relativeURLs.values()) {
      URL baseURL = new URL(UNICODE_DOT_ORG_URL + relativeURL);
      String versionedDirectoryListing = getPageContent(baseURL);

      // As of version 4.1.0, UnicodeData.txt lives in the ucd/ subdir.
      if (-1 != versionedDirectoryListing.indexOf("<a href=\"ucd/\">")) {
        baseURL = new URL(baseURL, "ucd/");
        relativeURL = relativeURL + "ucd/";
        versionedDirectoryListing = getPageContent(baseURL);
      }

      for (DataFileType fileType : DataFileType.values()) {
        if (null == dataFiles.get(fileType)) {
          String fileName = fileType.getFileName(versionedDirectoryListing);
          if (null != fileName) {
            dataFiles.put(fileType, new URL(baseURL, fileName));
          }
        }
      }
    }
    if (null != dataFiles.get(DataFileType.UNICODE_DATA)) { // Non-beta version found
      UnicodeVersion unicodeVersion = new UnicodeVersion(version, dataFiles);
      unicodeVersion.fetchAndParseDataFiles(getLog());
      unicodeVersions.put(unicodeVersion.majorMinorVersion, unicodeVersion);
      getLog().info("Completed downloading and parsing Unicode "
                    + unicodeVersion.majorMinorVersion + " data.\n");
    }
  }

  /**
   * Fetches the contents of the page at the given URL.
   *
   * @param url The location of the page to fetch.
   * @return The contents of the fetched page
   * @throws IOException If there is an error fetching the given page.
   */
  private String getPageContent(URL url) throws IOException {
    InputStreamReader reader = new InputStreamReader(url.openStream(), "UTF-8");
    StringBuilder builder = new StringBuilder();
    char[] buf = new char[BUF_SIZE];
    int charsRead;
    while ((charsRead = reader.read(buf)) > 0) {
      builder.append(buf, 0, charsRead);
    }
    return builder.toString();
  }

  /**
   * Writes out UnicodeProperties.java based on data fetched from unicode.org.
   *
   * @throws Exception If there is an error writing the output file.
   */
  private void emitUnicodeProperties() throws Exception {
    StringBuilder builder = new StringBuilder();
    UnicodePropertiesSkeleton skeleton
      = new UnicodePropertiesSkeleton(SKELETON_FILENAME);
    skeleton.emitNext(builder); // Header
    emitClassComment(builder);
    // Class declaration, static vars and fixed method definitions, part 1
    skeleton.emitNext(builder);
    emitInitBody(builder);
    skeleton.emitNext(builder); // Fixed method definitions, part 2; etc.
    emitUnicodeVersionsString(builder);
    skeleton.emitNext(builder); // Closing braces
    writeOutputFile(builder);
  }

  private void emitUnicodeVersionsString(StringBuilder builder) {
    builder.append("              \"");
    boolean isFirst = true;
    for (String majorMinorVersion : unicodeVersions.keySet()) {
      if (isFirst) {
        isFirst = false;
      } else {
        builder.append(", ");
      }
      if (majorMinorVersion.indexOf(".0") == majorMinorVersion.length() - 2) {
        String majorVersion
          = majorMinorVersion.substring(0, majorMinorVersion.indexOf("."));
        builder.append(majorVersion).append(", ");
      }
      builder.append(majorMinorVersion).append(", ")
        .append(unicodeVersions.get(majorMinorVersion).majorMinorUpdateVersion);
    }
    builder.append("\"");
  }

  private void emitVersionedUnicodeData() throws IOException {
    for (UnicodeVersion unicodeVersion : unicodeVersions.values()) {
      unicodeVersion.emitToDir(new File(outputDirectory, "data"));
    }
  }

  private void emitClassComment(StringBuilder builder) {
    builder.append("\n/**\n")   // emit Class comment
      .append(" * This class was automatically generated by")
      .append(" jflex-unicode-maven-plugin based\n")
      .append(" * on data files downloaded from unicode.org on ")
      .append(date).append(".\n */");
  }

  private void emitInitBody(StringBuilder builder) {
    boolean isFirst = true;
    for (String majorMinorVersion : unicodeVersions.keySet()) {
      if (isFirst) {
        builder.append("    if (");
        isFirst = false;
      } else {
        builder.append("    } else if (");
      }
      if (majorMinorVersion.indexOf(".0") == majorMinorVersion.length() - 2) {
        String majorVersion
          = majorMinorVersion.substring(0, majorMinorVersion.indexOf("."));
        builder.append("version.equals(\"").append(majorVersion)
          .append("\") || ");
      }
      UnicodeVersion unicodeVersion = unicodeVersions.get(majorMinorVersion);
      String versionSuffix = unicodeVersion.getVersionSuffix();
      builder.append("version.equals(\"").append(majorMinorVersion)
        .append("\") || version.equals(\"")
        .append(unicodeVersion.majorMinorUpdateVersion).append("\")) {\n")
        .append("      bind(Unicode").append(versionSuffix).append(".propertyValues")
        .append(", Unicode").append(versionSuffix).append(".intervals")
        .append(", Unicode").append(versionSuffix).append(".propertyValueAliases")
        .append(",\n         Unicode").append(versionSuffix).append(".maximumCodePoint")
        .append(", Unicode").append(versionSuffix).append(".caselessMatchPartitions")
        .append(", Unicode").append(versionSuffix).append(".caselessMatchPartitionSize")
        .append(");\n");
    }
    builder.append("    } else {\n")
      .append("      throw new UnsupportedUnicodeVersionException();\n")
      .append("    }\n");
  }

  /**
   * Writes the contents of the given StringBuilder out to
   * UnicodeProperties.java.
   *
   * @param builder What to write out
   * @throws IOException If there is an error writing out UnicodeProperties.java.
   */
  private void writeOutputFile(StringBuilder builder) throws IOException {
    PrintWriter writer = new PrintWriter(getOutputFile(), "UTF-8");
    writer.write(builder.toString());
    writer.flush();
    writer.close();
  }

  /**
   * Constructs and returns the name of the output file, based on the name
   * of the output class {@value #OUTPUT_CLASS_NAME}.
   *
   * @return The name of the output file.
   */
  private File getOutputFile() {
    return new File(outputDirectory, OUTPUT_CLASS_NAME + ".java");
  }
}
