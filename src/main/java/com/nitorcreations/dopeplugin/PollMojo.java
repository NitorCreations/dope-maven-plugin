package com.nitorcreations.dopeplugin;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;

import static java.nio.file.StandardWatchEventKinds.*;
import static java.nio.file.StandardCopyOption.*;

import org.apache.commons.io.FileUtils;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

@Mojo( name = "poll" )
public class PollMojo extends DopeMojo {
	@Parameter( defaultValue = "${project.build.resources[0].directory}" )
	private File resourcesDirectory;
	
	public void execute() throws MojoExecutionException {
		super.execute();
		Path htmlPath = htmlDirectory.toPath();
		Path markdownPath = markdownDirectory.toPath();
		Path htmlSources = new File(resourcesDirectory, "html").toPath();
		Path markdownSources = new File(resourcesDirectory, "markdown").toPath();
		
		WatchKey markdownKey = null;
		WatchKey htmlSrcKey = null;
		WatchKey markdownSrcKey = null;
		
		WatchService watcher = null;
		try {
			watcher = FileSystems.getDefault().newWatchService();
			markdownKey = markdownPath.register(watcher, ENTRY_CREATE,
					ENTRY_DELETE,
					ENTRY_MODIFY);
			htmlSrcKey = htmlSources.register(watcher, ENTRY_CREATE,
					ENTRY_DELETE,
					ENTRY_MODIFY);
			markdownSrcKey = markdownSources.register(watcher, ENTRY_CREATE,
					ENTRY_DELETE,
					ENTRY_MODIFY);
		} catch (IOException e) {
			throw new MojoExecutionException("Failed to get watcher", e);
		}
		for (;;) {
			WatchKey key;
			try {
				key = watcher.take();
			} catch (InterruptedException x) {
				return;
			}
			if (key == htmlSrcKey || key == markdownSrcKey) {
			    for (WatchEvent<?> event: key.pollEvents()) {
			        WatchEvent.Kind<?> kind = event.kind();
			        if (kind == OVERFLOW) {
			            continue;
			        }
			        @SuppressWarnings("unchecked")
					WatchEvent<Path> ev = (WatchEvent<Path>)event;
		        	try {
				        Path filename = ev.context();
		        		File tmpFile = File.createTempFile(filename.getFileName().toString(), ".tmp", new File(project.getBuild().getDirectory()));
		        		Path sourceFile;
		        		Path targetFile;
		        		if (key == htmlSrcKey) {
		        			sourceFile = Paths.get(htmlSources.toAbsolutePath().toString(), filename.toString());
		        			targetFile = Paths.get(htmlDirectory.getAbsolutePath(), filename.getFileName().toString());
		        		} else {
		        			sourceFile = Paths.get(markdownSources.toAbsolutePath().toString(), filename.toString());
		        			targetFile = Paths.get(markdownDirectory.getAbsolutePath(), filename.getFileName().toString());
		        		}
						getLog().info("Moving " + filename);
						if (kind == ENTRY_DELETE && targetFile.toFile().exists()) {
							Files.delete(targetFile);
						} else {
							Files.copy(sourceFile, tmpFile.toPath(), REPLACE_EXISTING);
							Files.move(tmpFile.toPath(), targetFile, ATOMIC_MOVE);
						}
					} catch (IOException e) {
						throw new MojoExecutionException("Failed to move sources", e);
					}
			    }
				boolean valid = key.reset();
				if (!valid) {
					break;
				}
			    continue;
			} else {
				getLog().info("Change in sources - building");
			    for (WatchEvent<?> event: key.pollEvents()) {
			        WatchEvent.Kind<?> kind = event.kind();
			        if (kind == OVERFLOW) {
			            continue;
			        }
			        @SuppressWarnings("unchecked")
					WatchEvent<Path> ev = (WatchEvent<Path>)event;
			        getLog().info("Change: " + ev.context());
			    }
			    boolean valid = key.reset();
			    super.execute();
			    if (!valid) {
			    	break;
			    }
			}
		}
	}
}
