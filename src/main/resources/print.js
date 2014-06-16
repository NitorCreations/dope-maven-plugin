var page = require('webpage').create(),
    system = require('system'),
    outname, fs = require("fs");

String.prototype.endsWith = function(suffix) {
    return this.indexOf(suffix, this.length - suffix.length) !== -1;
};

if (system.args.length > 2) {
  outname=system.args[2];
} else {
  outname=system.args[1].replace('.html', '.pdf');
}

if (fs.exists(outname)) {
  fs.remove(outname);
}

page.paperSize = { format: 'A4', 
    orientation: 'portrait', 
     border: '0cm' }

page.open(system.args[1], function () {
  window.setTimeout(function () {
    page.render(outname);
    phantom.exit();
  }, 200);
});
