package com.nitorcreations.dopeplugin;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.util.concurrent.Callable;

class StreamToString implements Callable<String> {
  private final InputStream in;
  private final ByteArrayOutputStream out;
  private final Charset charset;
  
  public StreamToString(InputStream in, Charset charset) {
    this.in = in;
    this.charset = charset;
    out = new ByteArrayOutputStream();
  }
  public StreamToString(InputStream in) {
	  this(in, Charset.defaultCharset());
  }
  @Override
  public String call() throws Exception {
    byte[] buffer = new byte[4 * 1024];
    try {
      int read;
      while ((read = in.read(buffer)) >= 0) {
        out.write(buffer, 0, read);
      }
    } catch (IOException e) {
      e.printStackTrace();
    }
    return new String(out.toByteArray(), charset);
  }
}