# File Trekker
This Project can be used to analyze files and Git Repositories. 
While the modes are tailored for my use case, it will be possible to change parts of it, to support another use case.

As part of a student research project, I analyzed the Signatures of the [Cuckoo](https://github.com/cuckoosandbox/community/) and [Cape](https://github.com/kevoreilly/community/) Community repositories.

## Usage
Calling ``java -jar FileTrekker-%VERSION%-jar-with-dependencies.jar`` without any Arguments will print out the CLI Help.

## Build
This project is using Maven.
To build a runnable jar use ``mvn package assembly:single``
