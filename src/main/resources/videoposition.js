var page = require('webpage').create(),
    system = require('system'),
    fs = require("fs");

page.onError = function (msg, trace) {
    console.log(msg);
    trace.forEach(function(item) {
        console.log('  ', item.file, ':', item.line);
    })
}
page.onAlert = function(msg) {
	console.log(msg);
};
	
	
page.viewportSize = { width: 1920, height: 1080 };
page.open(system.args[1], function() {
        var videoElement = page.evaluate(function() {
	    return document.querySelector("video");
	}); 
	if (videoElement) {
                var videoSource = page.evaluate(function() {
	    		return document.querySelector("video source").getAttribute("src");
		});
		var imgSource = page.evaluate(function() {
	    		return document.querySelector("video img").getAttribute("src");
		}); 
                console.log(videoSource);
                console.log(imgSource);
                var videos = page.evaluate(function() {
		    return document.querySelector("img").getBoundingClientRect();
		});
		console.log(videos.left);
		console.log(videos.top);
		console.log(videos.right  - videos.left);
		console.log(videos.bottom  - videos.top);
	}
	phantom.exit();
});
