package com.nitorcreations.presentationplugin;

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

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;

import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

import com.github.rjeschke.txtmark.Processor;

import java.io.File;
import java.io.FileWriter;
import java.io.FilenameFilter;
import java.io.IOException;

@Mojo( name = "render", defaultPhase = LifecyclePhase.COMPILE )
public class PresentationMojo extends AbstractMojo
{
	@Parameter( defaultValue = "${project.build.directory}/classes/markdown", property = "markdownDir", required = true )
	private File markdownDirectory;
	
	@Parameter( defaultValue = "${project.build.directory}/classes/html", property = "htmlDir", required = true )
    private File htmlDirectory;

    @Parameter( defaultValue = "${project.build.directory}", property = "buildDir", required = true )
    private File buildDirectory;

    @Parameter( defaultValue = "${project.groupId}.css", property = "css", required = true )
    private String css;
    
    public void execute() throws MojoExecutionException {
    	File f = markdownDirectory;
    	getLog().debug(String.format("Markdown from %s\n", f.getAbsolutePath()));
    	if ( !f.exists() ) {
    		return;
    	}
    	final File out = htmlDirectory;
    	getLog().debug(String.format("HTML to %s\n", out.getAbsolutePath()));
    	if (!out.exists()) {
    		out.mkdirs();
    	}
    	final File[] sources = f.listFiles(new FilenameFilter() {
			
			public boolean accept(File dir, String name) {
				return name.endsWith(".md") || name.endsWith(".md.notes");
			}
		});
    	Thread[] execs = new Thread[sources.length];
    	for (int i=0; i<sources.length;i++) {
    		final File nextSource = sources[i];
    		System.out.printf("Starting to process %s\n", nextSource.getAbsolutePath());
        	Thread next = new Thread(new Runnable() {
				public void run() {
					try {
						String slideName = nextSource.getName().substring(0, nextSource.getName().length() - 3);
						String nextHtml = Processor.process(nextSource);
						if (nextSource.getName().endsWith(".md")) {
							File outHtml = new File(out, slideName + ".html");
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
						}
						System.out.println(nextHtml);
					} catch (IOException e) {
						e.printStackTrace();
					}
				}
			});
    		execs[i] = next;
    		next.start();
    	}
    	for (Thread next : execs) {
    		try {
				next.join();
			} catch (InterruptedException e) {
				e.printStackTrace();
			}
    	}
    }
}
