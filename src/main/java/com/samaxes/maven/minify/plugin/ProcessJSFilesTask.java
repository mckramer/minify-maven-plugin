/*
 * Minify Maven Plugin
 * https://github.com/samaxes/minify-maven-plugin
 *
 * Copyright (c) 2009 samaxes.com
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
package com.samaxes.maven.minify.plugin;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.util.List;

import org.apache.maven.plugin.logging.Log;

import com.google.common.collect.Lists;
import com.google.javascript.jscomp.Compiler;
import com.google.javascript.jscomp.CompilerOptions;
import com.google.javascript.jscomp.SourceFile;
import com.google.javascript.jscomp.SourceMap;
import com.google.javascript.rhino.head.EvaluatorException;
import com.samaxes.maven.minify.common.ClosureConfig;
import com.samaxes.maven.minify.common.JavaScriptErrorReporter;
import com.samaxes.maven.minify.common.YuiConfig;
import com.samaxes.maven.minify.plugin.MinifyMojo.Engine;
import com.yahoo.platform.yui.compressor.JavaScriptCompressor;

/**
 * Task for merging and compressing JavaScript files.
 */
public class ProcessJSFilesTask extends ProcessFilesTask {

    private static final String SOURCE_MAP_SUFFIX = ".map";
	
	private final ClosureConfig closureConfig;

    /**
     * Task constructor.
     *
     * @param log Maven plugin log
     * @param verbose display additional info
     * @param bufferSize size of the buffer used to read source files
     * @param charset if a character set is specified, a byte-to-char variant allows the encoding to be selected.
     *        Otherwise, only byte-to-byte operations are used
     * @param suffix final file name suffix
     * @param nosuffix whether to use a suffix for the minified file name or not
     * @param skipMerge whether to skip the merge step or not
     * @param skipMinify whether to skip the minify step or not
     * @param skipSourceMap whether to skip the source mapping step or not
     * @param webappSourceDir web resources source directory
     * @param webappTargetDir web resources target directory
     * @param inputDir directory containing source files
     * @param sourceFiles list of source files to include
     * @param sourceIncludes list of source files to include
     * @param sourceExcludes list of source files to exclude
     * @param outputDir directory to write the final file
     * @param outputFilename the output file name
     * @param engine minify processor engine selected
     * @param yuiConfig YUI Compressor configuration
     * @param closureConfig Google Closure Compiler configuration
     */
    public ProcessJSFilesTask(Log log, boolean verbose, Integer bufferSize, String charset, String suffix,
            boolean nosuffix, boolean skipMerge, boolean skipMinify, boolean skipSourceMap, String webappSourceDir, 
            String webappTargetDir, String inputDir, List<String> sourceFiles, List<String> sourceIncludes, 
            List<String> sourceExcludes, String outputDir, String outputFilename, Engine engine, YuiConfig yuiConfig, 
            ClosureConfig closureConfig) {
        super(log, verbose, bufferSize, charset, suffix, nosuffix, skipMerge, skipMinify, skipSourceMap, 
        		webappSourceDir, webappTargetDir, inputDir, sourceFiles, sourceIncludes, sourceExcludes, outputDir, 
        		outputFilename, engine, yuiConfig);

        this.closureConfig = closureConfig;
    }

    /**
     * Minifies a JavaScript file.
     */
    @Override
    protected void minify(List<File> sourceFiles, File mergedFile, File minifiedFile) throws IOException {
        try (InputStream in = new FileInputStream(mergedFile);
                OutputStream out = new FileOutputStream(minifiedFile);
                InputStreamReader reader = new InputStreamReader(in, charset);
                OutputStreamWriter writer = new OutputStreamWriter(out, charset)) {
            log.info("Creating the minified file [" + ((verbose) ? minifiedFile.getPath() : minifiedFile.getName())
                    + "].");

            switch (engine) {
                case CLOSURE:
                    log.debug("Using Google Closure Compiler engine.");

                    CompilerOptions options = new CompilerOptions();
                    closureConfig.getCompilationLevel().setOptionsForCompilationLevel(options);
                    options.setOutputCharset(charset);
                    options.setLanguageIn(closureConfig.getLanguage());
                    
                    File sourceMapFile = null;
                    
                    if (!skipSourceMap) {
                    	sourceMapFile = new File(minifiedFile.getPath() + SOURCE_MAP_SUFFIX);
                        options.setSourceMapFormat(SourceMap.Format.V3);
                        options.setSourceMapOutputPath(sourceMapFile.getPath());
                        options.setSourceMapLocationMappings(Lists.newArrayList(
                        		new SourceMap.LocationMapping(sourceDir.getPath() + File.separator, "")));
                    }
                    
                    List<SourceFile> externs = closureConfig.getExterns();
                    List<SourceFile> sources = Lists.newArrayListWithCapacity(sourceFiles.size());
                    for (File file : sourceFiles){
                    	sources.add(SourceFile.fromFile(file));
                    }

                    Compiler compiler = new Compiler();
                    compiler.compile(externs, sources, options);

                    if (compiler.hasErrors()) {
                        throw new EvaluatorException(compiler.getErrors()[0].description);
                    }

                    writer.append(compiler.toSource());
                    
                    if (!skipSourceMap) {
                        log.info("Creating the minified source map file [" 
                        		+ (verbose ? sourceMapFile.getPath() : sourceMapFile.getName()) + "].");
                        sourceMapFile.createNewFile();
                        try (FileWriter mapOut = new FileWriter(sourceMapFile);) {
                        	compiler.getSourceMap().appendTo(mapOut, minifiedFile.getName());
                        } catch (IOException e) {
                            log.error("Failed to build source map file [" + sourceMapFile.getName() + "].", e);
                            throw e;
                        }
                        writer.append(System.getProperty("line.separator"));
                        writer.append("//# sourceMappingURL=" + sourceMapFile.getName());
                    }
                    
                    break;
                case YUI:
                    log.debug("Using YUI Compressor engine.");

                    JavaScriptCompressor compressor = new JavaScriptCompressor(reader, new JavaScriptErrorReporter(log,
                            mergedFile.getName()));
                    compressor.compress(writer, yuiConfig.getLinebreak(), yuiConfig.isMunge(), verbose,
                            yuiConfig.isPreserveAllSemiColons(), yuiConfig.isDisableOptimizations());
                    break;
                default:
                    log.warn("JavaScript engine not supported.");
                    break;
            }
        } catch (IOException e) {
            log.error("Failed to compress the JavaScript file [" + mergedFile.getName() + "].", e);
            throw e;
        }

        logCompressionGains(mergedFile, minifiedFile);
    }
}
