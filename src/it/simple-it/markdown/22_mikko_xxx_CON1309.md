# Implementation of Continuous Delivery#

##Extends continuous integration##
* Automated rapid, repeatable, reliable & low risk deployments
* Automated acceptance testing
* Push-button promote (=copy) from uat to production -> rollback for free

##What was out of scope##
* DDL updates (were done nondestructively beforehand)

## Round robin update of nodes##
* Node automatically taken out from LB for duration
* No need for update windows
