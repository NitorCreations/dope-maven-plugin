var page = require('webpage').create(),
    system = require('system'),
    outname, fs = require("fs");

String.prototype.endsWith = function(suffix) {
    return this.indexOf(suffix, this.length - suffix.length) !== -1;
};

if (system.args.length > 2) {
  outname=system.args[2];
} else {
  outname=system.args[1].replace('.html', '.png');
}
if (outname.endsWith(".pdf")) {
  page.viewportSize = { width: 1920 * 1.35, height: 1080 * 1.35};
} else {
  page.viewportSize = { width: 1920, height: 1080 };
}

page.open(system.args[1], function () {
  window.setTimeout(function () {
    page.render(outname);
    phantom.exit();
  }, 200);
});

