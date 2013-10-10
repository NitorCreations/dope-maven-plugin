# Nashorn JS engine #
* replaces rhino as JVM built-in JS engine
* upto 2-5x faster on most benchmarks
* rewritten version of node.js - faster and smaller

# JavaScript is a nasty, nasty, nasty language cont...#
* ‘4’ - 2 === 2, but ‘4’ + 2 === ’42’
* Number(“0xffgarbage”) === 255
* Math.min() > Math.max() === true
* right shift floating point numbers...
* _a.x_ looks like field a access
  * Could easily be getter (with side effects), _a_ could be as well
