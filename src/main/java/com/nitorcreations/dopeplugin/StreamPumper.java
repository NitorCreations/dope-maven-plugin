package com.nitorcreations.dopeplugin;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.Callable;

class StreamPumper implements Callable<Long> {
  private final InputStream in;
  private final OutputStream out;
  
  public StreamPumper(InputStream in, OutputStream out) {
    this.in = in;
    this.out = out; 
  }
  @Override
  public Long call() throws Exception {
    byte[] buffer = new byte[4 * 1024];
    long written = 0;
    try {
      int read;
      while ((read = in.read(buffer)) >= 0) {
        out.write(buffer, 0, read);
        written += read;
      }
    } catch (IOException e) {
      e.printStackTrace();
    }
    return written;
  }
}