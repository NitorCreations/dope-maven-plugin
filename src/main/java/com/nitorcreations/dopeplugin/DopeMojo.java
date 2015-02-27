package com.nitorcreations.dopeplugin;

import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.FilenameFilter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.StringWriter;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

import javax.imageio.ImageIO;

import org.apache.commons.lang.WordUtils;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.apache.pdfbox.exceptions.COSVisitorException;
import org.apache.pdfbox.util.PDFMergerUtility;
import org.apache.velocity.Template;
import org.apache.velocity.VelocityContext;
import org.apache.velocity.app.VelocityEngine;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.graph.Dependency;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.resolution.ArtifactRequest;
import org.eclipse.aether.resolution.ArtifactResolutionException;
import org.eclipse.aether.resolution.ArtifactResult;
import org.imgscalr.Scalr;
import org.pegdown.Extensions;
import org.pegdown.LinkRenderer;
import org.pegdown.PegDownProcessor;
import org.pegdown.ToHtmlSerializer;
import org.pegdown.ast.RootNode;
import org.pegdown.ast.VerbatimNode;
import org.python.util.PythonInterpreter;

@Mojo( name = "render", defaultPhase = LifecyclePhase.COMPILE )
public class DopeMojo extends AbstractMojo {
	@Parameter( defaultValue = "${project.build.directory}/classes/markdown", property = "markdownDir", required = true )
	protected File markdownDirectory;

	@Parameter( defaultValue = "${project.build.directory}/classes/html", property = "htmlDir", required = true )
	protected File htmlDirectory;

	protected File htmlTemplate;
	protected File titleTemplate;

	@Parameter( defaultValue = "${project.build.directory}/classes/slides", property = "buildDir", required = true )
	protected File slidesDirectory;

	@Parameter( defaultValue = "${project.build.directory}/classes/slides-small", property = "buildDir", required = true )
	protected File smallSlidesDirectory;

	@Parameter( defaultValue = "${project.build.directory}", property = "buildDir", required = true )
	protected File buildDirectory;

	@Parameter( defaultValue = "${project.groupId}.css", property = "css", required = true )
	protected String css;

	@Parameter( defaultValue = "${project.name}", property = "name", required = true )
	protected String name;

	@Parameter( defaultValue = "${project}", required = true )
	protected MavenProject project;

	@Parameter( defaultValue = "", property = "pngoptimizer")
	protected String pngoptimizer;

	@Parameter( defaultValue = "UTF-8", property = "charset" )
	protected String charset;
	
	@Parameter( defaultValue = "false", property = "pdfonly" )
	protected boolean pdfonly;

	@Parameter( defaultValue = "false", property = "installedPhantomjs" )
	protected boolean installedPhantomjs;

	@Component
	private RepositorySystem system;

	@Parameter (required=true, readonly=true, defaultValue = "${project.remoteProjectRepositories}")
	private List<RemoteRepository> remotes;

	@Parameter (required=true, readonly=true, defaultValue = "${repositorySystemSession}")
	private RepositorySystemSession session;

    protected static File checkScript;
	protected static File renderScript;
	protected static File printScript;
	protected static File videoPositionScript;
	
	protected String phantomjs;

	static ExecutorService service = new ThreadPoolExecutor(Runtime.getRuntime().availableProcessors(), 
			Runtime.getRuntime().availableProcessors() * 4, 10, TimeUnit.SECONDS, new LinkedBlockingQueue<Runnable>(100));
	static ExecutorService cached = Executors.newCachedThreadPool();
	static {
		try {
			checkScript = extractFile("check.js", ".js");
			renderScript = extractFile("render.js", ".js");
			printScript = extractFile("print.js", ".js");
			videoPositionScript = extractFile("videoposition.js", ".js");
		} catch (IOException | InterruptedException | ExecutionException e) {
			throw new RuntimeException("Failed to create temporary resource", e);
		}
	}

	public final class RenderHtmlTask implements Callable<Throwable> {
		private final File out;
		private final Map<String, String> htmls;
		private final Map<String, String> notes;
		private final String markdown;
		private final boolean isSlide;
		private final String slideName;
		private final long lastModified;
		
		public final List<Future<Throwable>> children;

		private RenderHtmlTask(File nextSource, Map<String, String> htmls, Map<String, String> notes, List<Future<Throwable>> children) throws IOException {
			this(new String(Files.readAllBytes(Paths.get(nextSource.toURI())), Charset.defaultCharset()), 
					htmls, notes, children, nextSource.getName().endsWith(".md"), nextSource.getName().replaceAll("\\.md(\\.notes)?$", ""), nextSource.lastModified());
		}
		
		private RenderHtmlTask(String markdown, Map<String, String> htmls, Map<String, String> notes, 
 		         List<Future<Throwable>> children, boolean isSlide, String slideName, long lastModified) {
			this.out = htmlDirectory;
			this.markdown = markdown;
			this.htmls = htmls;
			this.notes = notes;
			this.children = children;
			this.isSlide = isSlide;
			this.slideName = slideName;
			this.lastModified = lastModified;
		}

		public Throwable call() {
			try {
				PegDownProcessor processor = new PegDownProcessor(Extensions.AUTOLINKS + Extensions.TABLES + Extensions.FENCED_CODE_BLOCKS);

				if (isSlide) {
					File htmlFinal = new File(out, slideName + ".html");
					RootNode astRoot = processor.parseMarkdown(markdown.toCharArray());
					String nextHtml = new PygmentsToHtmlSerializer().toHtml(astRoot);
					htmls.put(slideName, nextHtml);
					if (htmlFinal.exists()  && (htmlFinal.lastModified() >= lastModified)) {
						return null;
					}
					MergeHtml m = new MergeHtml(nextHtml, slideName, htmlFinal);
					m.merge();
					if (!pdfonly) {
						children.add(service.submit(new RenderPngPdfTask(htmlFinal, "png")));
						children.add(service.submit(new VideoPositionTask(htmlFinal)));
					}
					children.add(service.submit(new RenderPngPdfTask(htmlFinal, "pdf")));
				} else {
					RootNode astRoot = processor.parseMarkdown(markdown.toCharArray());
					String nextHtml = new PygmentsToHtmlSerializer().toHtml(astRoot);
					notes.put(slideName, nextHtml);
				}

			} catch (IOException e) {
				return e;
			}
			return null;
		}
	}

	public final class MergeHtml {
		private final String html;
		private final String slideName;
		private final String css;
		private final File out;
		private final File template;
		private final File htmlFinal;

		public MergeHtml(String html, String slideName, File htmlFinal) {
			this.html = html;
			this.slideName = slideName;
			this.css = DopeMojo.this.css;
			this.out = htmlDirectory;
			this.template = htmlTemplate;
			this.htmlFinal = htmlFinal;
		}

		public void merge() throws IOException {
			VelocityEngine ve = new VelocityEngine();
			ve.setProperty("resource.loader", "file");
			ve.setProperty("file.resource.loader.class", "org.apache.velocity.runtime.resource.loader.FileResourceLoader");
			ve.setProperty("file.resource.loader.path", "");
			ve.init();
			Template t = ve.getTemplate(template.getAbsolutePath(), charset);
			VelocityContext context = new VelocityContext();
			context.put("name", name);
			context.put("slideName", slideName);
			context.put("css", css);
			context.put("html", html);
			context.put("project", project);
			File nextOut = new File(out, slideName + ".html.tmp");
			FileWriter w = new FileWriter(nextOut);
			t.merge( context, w);
			w.flush();
			nextOut.renameTo(htmlFinal);
		}
	}

	public class RenderPngPdfTask implements Callable<Throwable> {
		private final File slides;
		private final File smallSlides;
		private final File nextSource;
		private final String format;
		protected File script;

		private RenderPngPdfTask(File nextSource, String format) {
			this.slides = slidesDirectory;
			this.smallSlides = smallSlidesDirectory;
			this.nextSource = nextSource;
			this.format = format;
			this.script = renderScript;
		}

		@Override
		public Throwable call() {
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
				return null;
			}
			String output="";
			try {
				output = execPhantomjs(script, nextSource, nextPngPdf);
			} catch (IOException | InterruptedException | ExecutionException e) {
				return e;
			}
			if (output.length() == 0) {
				nextPngPdf.renameTo(finalPngPdf);
				if ("png".equals(format)) {
					try {
						Throwable bt = new OptimizePngTask(finalPngPdf).call();
						BufferedImage image = ImageIO.read(finalPngPdf);
						BufferedImage smallImage =
								Scalr.resize(image, Scalr.Method.QUALITY, Scalr.Mode.FIT_TO_WIDTH,
										960, 0, Scalr.OP_ANTIALIAS);
						File nextSmallPng = new File(smallSlides, finalPngPdf.getName() + ".tmp");
						File finalSmallPng = new File(smallSlides, finalPngPdf.getName());
						ImageIO.write(smallImage, "png", nextSmallPng);
						nextSmallPng.renameTo(finalSmallPng);
						Throwable st = new OptimizePngTask(finalSmallPng).call();
						if (bt != null) {
							return bt;
						} else {
							return st;
						}
					} catch (IOException e) {
						return e;
					}
				}
			} else {
				return new Throwable(String.format("Failed to render %s '%s'.%s: %s", format, slideName, format, output));
			}
			return null;
		}
	}

	public class PrintTask extends RenderPngPdfTask {
		public PrintTask(File nextSource) {
			super(nextSource, "pdf");
			this.script = printScript;
		}
	}

	public final class VideoPositionTask implements Callable<Throwable> {
		private final File slides;
		private final File smallSlides;
		private final File nextSource;

		private VideoPositionTask(File nextSource) {
			this.slides = slidesDirectory;
			this.smallSlides = smallSlidesDirectory;
			this.nextSource = nextSource;
		}

		@Override
		public Throwable call() {
			String slideName = nextSource.getName().substring(0, nextSource.getName().length() - 5);
			File nextVideo = new File(slides, slideName + ".tmp.video");
			File finalVideo = new File(slides, slideName + ".video");
			File nextSmallVideo = new File(smallSlides, slideName + ".tmp.video");
			File finalSmallVideo = new File(smallSlides, slideName + ".video");
			if (finalVideo.exists() && (finalVideo.lastModified() >= nextSource.lastModified())) {
				return null;
			}
			String output;
			try {
				output = execPhantomjs(videoPositionScript, nextSource);
			} catch (IOException | InterruptedException | ExecutionException e) {
				return e;
			}
			if (output.length() > 0) {
				try (FileOutputStream out = new FileOutputStream(nextVideo);
						FileOutputStream smallOut = new FileOutputStream(nextSmallVideo);
						) {
					out.write(output.getBytes(Charset.defaultCharset()));
					out.flush();
					smallOut.write(output.getBytes(Charset.defaultCharset()));
					smallOut.flush();
				} catch (IOException e) {
					return e;
				}
				nextVideo.renameTo(finalVideo);
				nextSmallVideo.renameTo(finalSmallVideo);
			}
			return null;
		}
	}

	public final class OptimizePngTask implements Callable<Throwable> {
		
		private final File png;

		public OptimizePngTask(File png) {
			this.png = png;
		}
		
		@Override
		public Throwable call() {
			if (pngoptimizer == null || pngoptimizer.length() == 0) {
				return null;
			}
			VelocityEngine ve = new VelocityEngine();
			ve.init();
			VelocityContext context = new VelocityContext();
			context.put("png", png.getAbsolutePath());
			context.put("project", project);
			
			StringWriter out = new StringWriter();
			if (!ve.evaluate(context, out, "png", pngoptimizer)) {
				return new RuntimeException("Failed to merge optimizer template");
			}
			try {
				List<String> list = new ArrayList<String>();
				Matcher m = Pattern.compile("([^\"]\\S*|\".+?\")\\s*").matcher(out.toString().trim());
				while (m.find()) {
				    list.add(m.group(1).replace("\"", ""));
				}
				Process optimize = new ProcessBuilder(list).redirectErrorStream(true).start();
				final InputStream is = optimize.getInputStream();
				Thread pump = new Thread() {
					public void start() {
						InputStreamReader isr = new InputStreamReader(is);
						BufferedReader br = new BufferedReader(isr);
						String line;
						try {
							while ((line = br.readLine()) != null) {
								getLog().debug(line);
							}
						} catch (IOException e) {
						}
					}
				};
				pump.start();
				if (optimize.waitFor() != 0) {
					return new RuntimeException("Failed to run optimizer - check debug output for why");
				}
				pump.interrupt();
			} catch (IOException | InterruptedException | IllegalThreadStateException e) {
				return e;
			}
			return null;
		}
		
	}
	public class IndexTemplateTask implements Callable<Throwable> {
		private final File nextIndex;
		private final Map<String, String> htmls;
		private final Map<String, String> notes;
		private final List<String> slideNames;
		private final MavenProject project;

		private IndexTemplateTask(File nextIndex, Map<String, String> htmls, Map<String, String> notes, 
				List<String> slideNames) {
			this.nextIndex = nextIndex;
			this.htmls = htmls;
			this.notes = notes;
			this.slideNames = slideNames;
			this.project = DopeMojo.this.project;
		}

		@Override
		public Throwable call() {
			VelocityEngine ve = new VelocityEngine();
			ve.setProperty("resource.loader", "file");
			ve.setProperty("file.resource.loader.class", "org.apache.velocity.runtime.resource.loader.FileResourceLoader");
			ve.setProperty("file.resource.loader.path", "");
			ve.init();
			Template t = ve.getTemplate(nextIndex.getAbsolutePath(), charset);
			File nextOut = new File(nextIndex.getParent(), nextIndex.getName() + ".tmp");
			VelocityContext context = createContext();
			try (FileWriter w = new FileWriter(nextOut)){
				t.merge( context, w);
				w.flush();
				nextOut.renameTo(nextIndex);
			} catch (IOException e) {
				return e;
			}
			return null;
		}
		protected VelocityContext createContext() {
			VelocityContext context = new VelocityContext();
			context.put("name", name);
			context.put("htmls", htmls);
			context.put("notes", notes);
			context.put("slidenames", slideNames);
			context.put("project", project);
			context.put("css", css);
			return context;
		}
	}
	public class DefaultIndexTemplateTask extends IndexTemplateTask {
		private final Map<String, String> renderedIndexes;

		private DefaultIndexTemplateTask(File nextIndex, Map<String, String> htmls, Map<String, String> notes, 
				List<String> slideNames, Map<String, String> renderedIndexes) {
			super(nextIndex,htmls, notes, slideNames);
			this.renderedIndexes = renderedIndexes;
		}
		@Override
		protected VelocityContext createContext() {
			VelocityContext context = super.createContext();
			context.put("indexes", renderedIndexes);
			return context;
		}
	}
	public final class TitleTemplateTask extends IndexTemplateTask {
		private final List<Future<Throwable>> children;
		public TitleTemplateTask(List<Future<Throwable>> children) {
			super(titleTemplate, null, null, null);
			this.children = children;
		}
		@Override
		public Throwable call() {
			Throwable superRes = super.call();
			if (superRes == null) {
				if (!pdfonly) {
					children.add(service.submit(new RenderPngPdfTask(titleTemplate, "png")));
					children.add(service.submit(new VideoPositionTask(titleTemplate)));
				}
				children.add(service.submit(new RenderPngPdfTask(titleTemplate, "pdf")));
				return null;
			} else {
				return superRes;
			}
		}
	}

	private static File extractFile(String name, String suffix) throws IOException, InterruptedException, ExecutionException {
		File target = File.createTempFile(name.substring(0, name.length() - suffix.length()), suffix);
		target.deleteOnExit();
		try (FileOutputStream outStream = new FileOutputStream(target); 
				InputStream inStream = 
						DopeMojo.class.getClassLoader().getResourceAsStream(name)) {
			service.submit(new StreamPumper(inStream, outStream)).get();
			return target;
		}
	}

	public void execute() throws MojoExecutionException {
		File f = markdownDirectory;
		htmlTemplate = new File(htmlDirectory, "slidetemplate.html");
		titleTemplate = new File(htmlDirectory, "title.html");
		if (installedPhantomjs) {
			phantomjs = "phantomjs";
			if (System.getProperty("os.name").toLowerCase().contains("win")) {
				phantomjs = "phantomjs.exe";
			}
		} else {
			try {
				phantomjs = resolvePhantomJs();
			} catch (IOException | InterruptedException | ExecutionException e) {
				throw new MojoExecutionException("Failed to resolve phantomjs binary", e);
			}
		}
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
		final Map<String, String> notes = new ConcurrentHashMap<>();
		final Map<String, String> htmls = new ConcurrentHashMap<>();
		final List<Future<Throwable>> execs = new ArrayList<>();
		final List<Future<Throwable>> children = new CopyOnWriteArrayList<>();
		final ArrayList<String> slideNames = new ArrayList<>();
		for (int i=0; i<sources.length;i++) {			
			final File nextSource = sources[i];
			String fileName = nextSource.getName();
			String slideName;
			boolean isSlide = true;
			if (fileName.endsWith(".md")) {
				slideName = fileName.substring(0, fileName.length() - 3);
			} else {
				slideName = fileName.substring(0, fileName.length() - 9);
				isSlide = false;
			}
			getLog().debug(String.format("Starting to process %s", nextSource.getAbsolutePath()));
			try {
				String nextMarkdown = new String(Files.readAllBytes(Paths.get(nextSource.toURI())), Charset.forName(charset));
				int slideStart = 0;
				int nextStart = nextMarkdown.indexOf("<!--break", slideStart);
				boolean nextIsSlide = isSlide;
				int index=0;
				while (nextStart > -1) {
					String slideId = slideName + "$" + String.format("%03d", index);
					if (nextIsSlide) {
						slideNames.add(slideId);
						index++;
					} else {
						slideId = slideName + "$" + String.format("%03d", (index-1));
					}
					execs.add(service.submit(new RenderHtmlTask(nextMarkdown.substring(slideStart, nextStart), htmls, notes, children, nextIsSlide, slideId, nextSource.lastModified())));
					nextIsSlide = !nextMarkdown.regionMatches(nextStart, "<!--break:notes", 0, "<!--break:notes".length());
					slideStart = nextStart;
					nextStart = nextMarkdown.indexOf("<!--break", slideStart + 1);
				}
				String slideId = slideName + "$" + String.format("%03d", index);
				if (nextIsSlide) {
					slideNames.add(slideId);
				} else {
					slideId = slideName + "$" + String.format("%03d", (index-1));
				}
				execs.add(service.submit(new RenderHtmlTask(nextMarkdown.substring(slideStart), htmls, notes, children, nextIsSlide, slideId, nextSource.lastModified())));
			} catch (IOException e) {
				getLog().error(e);
			}
		}
		if (titleTemplate != null && titleTemplate.exists()) { 
			execs.add(service.submit(new TitleTemplateTask(children)));
		}
		

		for (Future<Throwable> next : execs) {
			Throwable nextT;
			try {
				nextT = next.get();
				if (nextT != null) {
					getLog().error(nextT);
				}
			} catch (InterruptedException | ExecutionException e) {
				getLog().error(e);
			}
		}
		Collections.sort(slideNames);
		if (titleTemplate.exists()) {
			slideNames.add(0, "title");
		}
		LinkedHashMap<String, String> renderedIndexes = new LinkedHashMap<>();
		LinkedHashMap<String, String> index = new LinkedHashMap<String, String>();
		index.put("run", "Run presentation");
		index.put("follow", "Follow presentation");
		index.put("reveal", "Web version of the presentation");
		index.put("notes", "Slides and presenter notes for printing");
		for (String nextIndex : index.keySet()) {
			File nextIndexFile = new File(htmlDirectory, "index-"  + nextIndex + ".html");
			if (nextIndexFile.exists()) {
		      children.add(service.submit(new IndexTemplateTask(nextIndexFile, htmls, notes, slideNames)));
		      renderedIndexes.put(nextIndex, index.get(nextIndex));
			}
		}
		children.add(service.submit((new DefaultIndexTemplateTask(new File(htmlDirectory, "index-default.html"), htmls, notes, slideNames, renderedIndexes))));

		for (Future<Throwable> next : children) {
			Throwable nextT;
			try {
				nextT = next.get();
				if (nextT != null) {
					getLog().error(nextT);
				}
			} catch (InterruptedException | ExecutionException e) {
				getLog().error(e);
			}
		}
		Throwable err = new PrintTask(new File(htmlDirectory, "index-notes.html")).call();
		if (err != null) {
			getLog().error("Creating presenter notes pdf failed", err);
		}
		new File(buildDirectory, "index-notes.pdf").renameTo(new File(htmlDirectory, "presentation-notes.pdf"));
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

	private static void ensureDir(File dir) throws MojoExecutionException {
		if (dir.exists() && !dir.isDirectory()) {
			throw new MojoExecutionException(String.format("%s exists and is not a directory", dir.getAbsolutePath()));
		}
		if (!dir.exists() && !dir.mkdirs()) {
			throw new MojoExecutionException(String.format("Failed to create directory %s", dir.getAbsolutePath()));
		}
	}

	private class PygmentsToHtmlSerializer extends ToHtmlSerializer {
		public PygmentsToHtmlSerializer() {
			this(new LinkRenderer());
		}
		public PygmentsToHtmlSerializer(LinkRenderer linkRenderer) {
			super(linkRenderer);
		}
		@Override
		public void visit(VerbatimNode node) {
			try {
				if (!node.getType().isEmpty()) {
					synchronized (this.getClass()) {
						try (PythonInterpreter interpreter = new PythonInterpreter()) {
							String lang = WordUtils.capitalize(node.getType());
							interpreter.set("code", node.getText());

							interpreter.exec("from pygments import highlight\n"
									+ "from pygments.lexers import " + lang + "Lexer\n"
									+ "from pygments.formatters import HtmlFormatter\n"
									+ "\nresult = highlight(code, " + lang + "Lexer(), HtmlFormatter())");

							String ret = interpreter.get("result", String.class); 
							if (ret != null) {
								printer.print(ret);
							} else {
								super.visit(node);
							}
						}
					}
				} else {
					super.visit(node);
				}
			} catch (Exception e) {
				e.printStackTrace();
				super.visit(node);
			}
		}
	}
	public String execPhantomjs(File renderScript, File nextSource, File nextPngPdf) throws IOException, InterruptedException, ExecutionException {
		ProcessBuilder b = new ProcessBuilder(phantomjs, renderScript.getAbsolutePath(), nextSource.getAbsolutePath(), nextPngPdf.getAbsolutePath());
		b.environment().putAll(System.getenv());
		Process p = b.start();
		p.waitFor();
		return cached.submit(new StreamToString(p.getInputStream())).get();
	}
	public String execPhantomjs(File renderScript, File nextSource) throws IOException, InterruptedException, ExecutionException {
		ProcessBuilder b = new ProcessBuilder(phantomjs, renderScript.getAbsolutePath(), nextSource.getAbsolutePath());
		b.environment().putAll(System.getenv());
		Process p = b.start();
		p.waitFor();
		return cached.submit(new StreamToString(p.getInputStream())).get();
	}
	private String resolvePhantomJs() throws IOException, InterruptedException, ExecutionException {
		Properties ver = new Properties();
		ver.load(this.getClass().getClassLoader().getResourceAsStream("dope.properties"));
		String artifactId = "com.nitorcreations:dope-maven-plugin:gz:";
		
		String os = System.getProperty("os.name").toLowerCase();
		String binaryFile = "phantomjs";
		if (os.contains("win")) {
			artifactId += "win:";
			binaryFile = "phantomjs.exe";
		} else if (os.contains("os x")) {
			artifactId += "osx:";
		} else {
			if (System.getProperty("os.arch").contains("64")) {
				artifactId += "lin64:";
			} else {
				artifactId += "lin32:";
			}
		}
		artifactId += ver.getProperty("dope.version");
	    Dependency dependency = new Dependency(new DefaultArtifact(artifactId), "runtime");
	    ArtifactRequest req = new ArtifactRequest();
	    req.setArtifact(dependency.getArtifact());
	    for (RemoteRepository remote : remotes) {
	    	req.addRepository(remote);
	    }
	    File binary = null;
	    try {
	      ArtifactResult result = system.resolveArtifact(session, req);
	      binary = result.getArtifact().getFile();
	    } catch (ArtifactResolutionException e) {
	      throw new RuntimeException("Failed to resolve " + artifactId, e);
	    }
    	File finalFile = new File(binary.getAbsoluteFile().getParentFile(), binaryFile);
	    try (GZIPInputStream in = new GZIPInputStream(new FileInputStream(binary)); 
	    		OutputStream out = new FileOutputStream(finalFile)) {
	    	cached.submit(new StreamPumper(in, out)).get();
	    }
	    finalFile.setExecutable(true);
	    return finalFile.getAbsolutePath();
	}
}
