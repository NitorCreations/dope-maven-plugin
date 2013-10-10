package com.nitorcreations.dopeplugin;

import com.nitorcreations.dopeplugin.DopeMojo.RenderTask;

public class TaskThread extends Thread {
	public final RenderTask task;
	public TaskThread(RenderTask task) {
		super(task);
		this.task = task;
	}

}
