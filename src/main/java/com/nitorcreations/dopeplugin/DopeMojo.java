package com.nitorcreations.dopeplugin;

/*
 * Copyright 2001-2005 The Apache Software Foundation.
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

import org.apache.commons.io.IOUtils;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.pdfbox.exceptions.COSVisitorException;
import org.apache.pdfbox.util.PDFMergerUtility;
import org.apache.velocity.Template;
import org.apache.velocity.VelocityContext;
import org.apache.velocity.app.VelocityEngine;
import org.imgscalr.Scalr;

import com.github.jarlakxen.embedphantomjs.PhantomJSReference;
import com.github.jarlakxen.embedphantomjs.executor.PhantomJSFileExecutor;
import com.github.jarlakxen.embedphantomjs.executor.PhantomJSSyncFileExecutor;
import com.github.rjeschke.txtmark.Processor;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.FilenameFilter;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringWriter;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

import javax.imageio.ImageIO;

@Mojo( name = "render", defaultPhase = LifecyclePhase.COMPILE )
public class DopeMojo extends AbstractMojo {
	@Parameter( defaultValue = "${project.build.directory}/classes/markdown", property = "markdownDir", required = true )
	private File markdownDirectory;
	
	@Parameter( defaultValue = "${project.build.directory}/classes/html", property = "htmlDir", required = true )
    private File htmlDirectory;

    @Parameter( defaultValue = "${project.build.directory}/classes/slides", property = "buildDir", required = true )
    private File slidesDirectory;

    @Parameter( defaultValue = "${project.build.directory}/classes/slides-small", property = "buildDir", required = true )
    private File smallSlidesDirectory;

    @Parameter( defaultValue = "${project.build.directory}", property = "buildDir", required = true )
    private File buildDirectory;

    @Parameter( defaultValue = "${project.groupId}.css", property = "css", required = true )
    private String css;
    
    @Parameter( defaultValue = "${project.name}", property = "name", required = true )
    private String name;
    
    private static File renderScript;
    private static File videoPositionScript;
    
    static {
		try {
			renderScript = extractFile("render.js", ".js");
			videoPositionScript = extractFile("videoposition.js", ".js");
		} catch (IOException e) {
    		throw new RuntimeException("Failed to create temporary resource", e);
		}
		
    	try (FileOutputStream videoPositionScritpOutStream = new FileOutputStream(videoPositionScript); 
    			InputStream videoPositionScriptStream = 
    					DopeMojo.class.getClassLoader().getResourceAsStream("videoposition.js")) {
    		renderScript.deleteOnExit();
    		IOUtils.copy(videoPositionScriptStream, videoPositionScritpOutStream);
    	} catch (IOException e) {
    		throw new RuntimeException("Failed to create videoposition script", e);
    	}
    	
    }
	
	public abstract class RenderTask implements Runnable {
		public Throwable error = null;
	}
	
	public final class RenderHtmlTask extends RenderTask {
		private final File out;
		private final File nextSource;
		private final Map<String, String> htmls;
		private final Map<String, String> notes;
		
		public final TaskThread[] children = new TaskThread[3];
		
		private RenderHtmlTask(File nextSource, Map<String, String> htmls, Map<String, String> notes) {
			this.out = htmlDirectory;
			this.nextSource = nextSource;
			this.htmls = htmls;
			this.notes = notes;
			children[0]=null;
			children[1]=null;
		}

		public void run() {
			try {
				if (nextSource.getName().endsWith(".md")) {
					String slideName = nextSource.getName().substring(0, nextSource.getName().length() - 3);
					File htmlFinal = new File(out, slideName + ".html");
					String nextHtml = Processor.process(nextSource);
					htmls.put(slideName, nextHtml);
					if (htmlFinal.exists()  && (htmlFinal.lastModified() >= nextSource.lastModified())) {
						return;
					}
					File outHtml = new File(out, slideName + ".html.tmp");
					FileWriter outWriter = new FileWriter(outHtml);
					outWriter.write("<!DOCTYPE html PUBLIC \"-//W3C//DTD XHTML 1.0 Transitional//EN\" \"http://www.w3.org/TR/xhtml1/DTD/xhtml1-transitional.dtd\">\n" +
							"<html xmlns=\"http://www.w3.org/1999/xhtml\">\n" +
							"<head>\n" +
							"  <meta http-equiv=\"Content-Type\" content=\"text/html; charset=utf-8\" />\n" +
							"  <meta http-equiv=\"Content-Style-Type\" content=\"text/css\" />\n" +
							"  <title>" + slideName + "</title>\n" +
							"  <style type=\"text/css\">code{white-space: pre;}</style>\n" +
							"  <link rel=\"stylesheet\" href=\"" + css + "\" type=\"text/css\" />\n" + 
							"</head>\n" +
							"<body>\n");
					outWriter.write(nextHtml);
					outWriter.write("</body>\n</html>\n");
					outWriter.flush();
					outWriter.close();
					outHtml.renameTo(htmlFinal);
					children[0] = new TaskThread(new RenderPngPdfTask(htmlFinal, "png"));
					children[1] = new TaskThread(new RenderPngPdfTask(htmlFinal, "pdf"));
					children[2] = new TaskThread(new VideoPositionTask(htmlFinal));
					children[0].start();
					children[1].start();
					children[2].start();
				} else {
					String slideName = nextSource.getName().substring(0, nextSource.getName().length() - ".md.notes".length());
					String nextHtml = Processor.process(nextSource);
					notes.put(slideName, nextHtml);
				}

			} catch (IOException e) {
				this.error = e;
				return;
			}
		}
	}
	
	public final class RenderPngPdfTask extends RenderTask {
		private final File slides;
		private final File smallSlides;
		private final File nextSource;
		private final String format;
		
		private RenderPngPdfTask(File nextSource, String format) {
			this.slides = slidesDirectory;
			this.smallSlides = smallSlidesDirectory;
			this.nextSource = nextSource;
			this.format = format;
		}

		@Override
		public void run() {
			String slideName = nextSource.getName().substring(0, nextSource.getName().length() - 5);
			File outFolder;
			if ("png".equals(format)) {
				outFolder = slides;
			} else {
				outFolder = buildDirectory;
			}
			File nextPngPdf = new File(outFolder, slideName + ".tmp." + format);
			File finalPngPdf = new File(outFolder, slideName + "." + format);
			if (finalPngPdf.exists() && (finalPngPdf.lastModified() >= nextSource.lastModified())) {
				return;
			}
			PhantomJSFileExecutor<String> ex = new PhantomJSSyncFileExecutor(PhantomJSReference.create().build());
			String output = ex.execute(renderScript, nextSource.getAbsolutePath(), nextPngPdf.getAbsolutePath());
			if (output.length() == 0) {
				nextPngPdf.renameTo(finalPngPdf);
				if ("png".equals(format)) {
					try {
						BufferedImage image = ImageIO.read(finalPngPdf);
						BufferedImage smallImage =
								Scalr.resize(image, Scalr.Method.QUALITY, Scalr.Mode.FIT_TO_WIDTH,
										960, 0, Scalr.OP_ANTIALIAS);
						File nextSmallPng = new File(smallSlides, finalPngPdf.getName() + ".tmp");
						File finalSmallPng = new File(smallSlides, finalPngPdf.getName());
						ImageIO.write(smallImage, "png", nextSmallPng);
						nextSmallPng.renameTo(finalSmallPng);
					} catch (IOException e) {
						this.error = e;
					}
				}
			} else {
				this.error = new Throwable(String.format("Failed to render %s '%s'.%s: %s", format, slideName, format, output));
				return;
			}
		}
	}
	public final class VideoPositionTask extends RenderTask {
		private final File slides;
		private final File smallSlides;
		private final File nextSource;
		
		private VideoPositionTask(File nextSource) {
			this.slides = slidesDirectory;
			this.smallSlides = smallSlidesDirectory;
			this.nextSource = nextSource;
		}

		@Override
		public void run() {
			String slideName = nextSource.getName().substring(0, nextSource.getName().length() - 5);
			File nextVideo = new File(slides, slideName + ".tmp.video");
			File finalVideo = new File(slides, slideName + ".video");
			File nextSmallVideo = new File(smallSlides, slideName + ".tmp.video");
			File finalSmallVideo = new File(smallSlides, slideName + ".video");
			if (finalVideo.exists() && (finalVideo.lastModified() >= nextSource.lastModified())) {
				return;
			}
			PhantomJSFileExecutor<String> ex = new PhantomJSSyncFileExecutor(PhantomJSReference.create().build());
			String output = ex.execute(videoPositionScript, nextSource.getAbsolutePath());
			if (output.length() > 0) {
				try (FileOutputStream out = new FileOutputStream(nextVideo);
						FileOutputStream smallOut = new FileOutputStream(nextSmallVideo);
				) {
					out.write(output.getBytes(Charset.defaultCharset()));
					out.flush();
					smallOut.write(output.getBytes(Charset.defaultCharset()));
					smallOut.flush();
				} catch (IOException e) {
					this.error = e;
					return;
				}
				nextVideo.renameTo(finalVideo);
				nextSmallVideo.renameTo(finalSmallVideo);
			}
		}
	}


	public final class IndexTemplateTask extends RenderTask {
		private final File nextIndex;
		private final Map<String, String> htmls;
		private final Map<String, String> notes;
		private final TreeSet<String> slideNames;
		
		private IndexTemplateTask(File nextIndex, Map<String, String> htmls, Map<String, String> notes, 
				TreeSet<String> slideNames) {
			this.nextIndex = nextIndex;
			this.htmls = htmls;
			this.notes = notes;
			this.slideNames = slideNames;
		}

		@Override
		public void run() {
	        VelocityEngine ve = new VelocityEngine();
	        ve.setProperty("resource.loader", "file");
	        ve.setProperty("file.resource.loader.class", "org.apache.velocity.runtime.resource.loader.FileResourceLoader");
	        ve.setProperty("file.resource.loader.path", "");
	        ve.init();
	        Template t = ve.getTemplate(nextIndex.getAbsolutePath());
	        VelocityContext context = new VelocityContext();
	        context.put("name", name);
	        context.put("htmls", htmls);
	        context.put("notes", notes);
	        context.put("slidenames", slideNames);
	        File nextOut = new File(nextIndex.getParent(), nextIndex.getName() + ".tmp");
			try (FileWriter w = new FileWriter(nextOut)){
		        t.merge( context, w);
		        w.flush();
		        nextOut.renameTo(nextIndex);
			} catch (IOException e) {
				this.error = e;
			}
		}
	}
    
    private static File extractFile(String name, String suffix) throws IOException {
    	File target = File.createTempFile(name.substring(0, name.length() - suffix.length()), suffix);
    	target.deleteOnExit();
    	try (FileOutputStream outStream = new FileOutputStream(target); 
    			InputStream inStream = 
    					DopeMojo.class.getClassLoader().getResourceAsStream(name)) {
    		IOUtils.copy(inStream, outStream);
    		return target;
    	}
    }
    
    public void execute() throws MojoExecutionException {
    	File f = markdownDirectory;
    	getLog().debug(String.format("Markdown from %s", f.getAbsolutePath()));
    	if ( !f.exists() ) {
    		return;
    	}
    	getLog().debug(String.format("HTML to %s", htmlDirectory.getAbsolutePath()));
    	ensureDir(htmlDirectory);
    	getLog().debug(String.format("Slides to %s", slidesDirectory.getAbsolutePath()));
    	ensureDir(slidesDirectory);
    	getLog().debug(String.format("Small slides to %s", smallSlidesDirectory.getAbsolutePath()));
    	ensureDir(smallSlidesDirectory);
    	final File[] sources = f.listFiles(new FilenameFilter() {
			
			public boolean accept(File dir, String name) {
				return name.endsWith(".md") || name.endsWith(".md.notes");
			}
		});
    	getLog().info(String.format("Processing %d markdown files", sources.length));
    	TaskThread[] execs = new TaskThread[sources.length];
    	final Map<String, String> notes = new ConcurrentHashMap<>();
    	final Map<String, String> htmls = new ConcurrentHashMap<>();
    	final TreeSet<String> slideNames = new TreeSet<>();
    	for (int i=0; i<sources.length;i++) {
    		final File nextSource = sources[i];
    		if (nextSource.getName().endsWith(".md")) {
    			slideNames.add(nextSource.getName().substring(0, nextSource.getName().length() - 3));
    		}
    		getLog().debug(String.format("Starting to process %s", nextSource.getAbsolutePath()));
        	TaskThread next = new TaskThread(new RenderHtmlTask(nextSource, htmls, notes));
    		execs[i] = next;
    		next.start();
    	}
    	
    	List<TaskThread> children = new ArrayList<>();
    	for (TaskThread next : execs) {
    		try {
				next.join();
				if (next.task.error != null) {
					getLog().error(next.task.error);
				} else {
					TaskThread nextThread = ((RenderHtmlTask)(next.task)).children[0];
					if (nextThread != null) {
						children.add(nextThread);
						children.add(((RenderHtmlTask)(next.task)).children[1]);
					}
				}
			} catch (InterruptedException e) {
				throw new MojoExecutionException("Tasks interrupted", e);
			}
    	}
    	TaskThread defaultIndex = new TaskThread(new IndexTemplateTask(new File(htmlDirectory, "index-default.html"), htmls, notes, slideNames));
    	TaskThread followIndex = new TaskThread(new IndexTemplateTask(new File(htmlDirectory, "index-follow.html"), htmls, notes, slideNames));
    	TaskThread runIndex = new TaskThread(new IndexTemplateTask(new File(htmlDirectory, "index-run.html"), htmls, notes, slideNames));
    	defaultIndex.start();
    	followIndex.start();
    	runIndex.start();
    	children.add(defaultIndex);
    	children.add(followIndex);
    	children.add(runIndex);
    	waitForTasks(children);
    	getLog().debug("Merging pdfs");
        PDFMergerUtility merger = new PDFMergerUtility();
        for( String sourceFileName : slideNames ) {
        	File source = new File(buildDirectory, sourceFileName + ".pdf");
        	getLog().debug("Merging pdf: " + source.getAbsolutePath());
            merger.addSource(source.getAbsolutePath());
        }
        String destinationFileName = new File(htmlDirectory, "presentation.pdf").getAbsolutePath();
        merger.setDestinationFileName(destinationFileName);
        try {
			merger.mergeDocuments();
		} catch (COSVisitorException | IOException e) {
			throw new MojoExecutionException("Failed to merge pdf", e);
		}

    }
    
    private void waitForTasks(List<TaskThread> children) throws MojoExecutionException {
    	for (TaskThread next : children) {
    		try {
    			next.join();
    			if (next.task.error != null) {
    				getLog().error(next.task.error);
    			}
    		} catch (InterruptedException e) {
    			throw new MojoExecutionException("Tasks interrupted", e);
    		}
    	}
    }
    
    private static void ensureDir(File dir) throws MojoExecutionException {
    	if (dir.exists() && !dir.isDirectory()) {
    		throw new MojoExecutionException(String.format("%s exists and is not a directory", dir.getAbsolutePath()));
    	}
    	if (!dir.exists() && !dir.mkdirs()) {
    		throw new MojoExecutionException(String.format("Failed to create directory %s", dir.getAbsolutePath()));
    	}
    	
    }
}
