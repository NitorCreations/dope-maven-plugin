# From Invokedynamic to Project Nashorn #

* first time a new bytecode was introduced in the history of the JVM specification!
* used for optimizing lambdas and non-java language implementations

## Method_Handle is a function pointer #

* Make a method call without standard JVM checks
* Enables completely custom linkage
* Essential for hotswap method call targets

## How it works ##

* JVM will ask your code which actual method to call on first invoke and optimize it to native code
* can add guard around that will be first invoked
* if guard fails the method handle is re-executed

