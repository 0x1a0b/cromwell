[![Build Status](https://travis-ci.org/broadinstitute/cromwell.svg?branch=develop)](https://travis-ci.org/broadinstitute/cromwell?branch=develop)
[![Coverage Status](https://coveralls.io/repos/broadinstitute/cromwell/badge.svg?branch=develop)](https://coveralls.io/r/broadinstitute/cromwell?branch=develop)


Cromwell
========

Workflow engine using [WDL](SPEC.md) as the workflow and task language.

API Documenation
----------------

API Documentation can be found [here](http://broadinstitute.github.io/cromwell/scaladoc).

Generate WDL Parser
-------------------

Install the latest version of [Hermes](http://github.com/scottfrazer/hermes), then run the following command within this directory:

```
hermes generate src/main/resources/grammar.hgr --language=java --directory=src/main/java --name=wdl --java-package=cromwell.parser --java-use-apache-commons
```

Generating and Hosting ScalaDoc
-------------------------------

Essentially run `sbt doc` then commit the generated code into the `gh-pages` branch on this repository

```
$ sbt doc
$ git co gh-pages
$ mv target/scala-2.11/api scaladoc
$ git add scaladoc
$ git commit -m "API Docs"
$ git push origin gh-pages
```

Architecture
------------

![Cromwell Architecture](http://i.imgur.com/kPPTe0l.png)

The architecture is split into four layers, from bottom to top:

### cromwell.parser

Contains only the WDL parser to convert WDL source code to an abstract syntax tree.  Clients should never need to interact with WDL at this level, though nothing specifically precludes that.

### cromwell.binding

Contains code that takes an abstract syntax tree and returns native Scala object representations of those ASTs.  This layer will also have functions for evaluating expressions when support for that is added.

### cromwell.backend

Contains implementations of an interface to launch jobs.  `cromwell.engine` will use this to execute and monitor jobs.

### cromwell.engine

Contains the Akka code and actor system to execute a workflow.  This layer should operate entirely on objects returned from the `cromwell.binding` layer.

Web Server
----------

The `server` subcommand on the executable JAR will start an HTTP server which can accept WDL files to run as well as check status and output of existing workflows.

### POST /workflows

This endpoint accepts a POST request with a `multipart/form-data` encoded body.  The two elements in the body must be named `wdl` and `inputs`.  The `wdl` element contains the WDL file to run while the `inputs` contains a JSON file of the inputs to the workflow.

```
$ http --print=hbHB --form POST localhost:8000/workflows wdlSource=@src/main/resources/3step.wdl workflowInputs@inputs.json
```

Request:

```
POST /workflows HTTP/1.1
Accept: */*
Accept-Encoding: gzip, deflate
Connection: keep-alive
Content-Length: 730
Content-Type: multipart/form-data; boundary=f1a9bd3079b14946bd1264efaa545c45
Host: localhost:8000
User-Agent: HTTPie/0.9.2

--f1a9bd3079b14946bd1264efaa545c45
Content-Disposition: form-data; name="wdlSource"

task ps {
  command {
    ps
  }
  output {
    File procs = "stdout"
  }
}

task cgrep {
  command {
    grep '${pattern}' ${File in_file} | wc -l
  }
  output {
    Int count = read_int("stdout")
  }
}

task wc {
  command {
    cat ${File in_file} | wc -l
  }
  output {
### POST /workflows
    Int count = read_int("stdout")
  }
}

workflow three_step {
  call ps
  call cgrep {
    input: in_file=ps.procs
  }
  call wc {
    input: in_file=ps.procs
  }
}

--f1a9bd3079b14946bd1264efaa545c45
Content-Disposition: form-data; name="workflowInputs"; filename="inputs.json"

{
    "three_step.cgrep.pattern": "..."
}

--f1a9bd3079b14946bd1264efaa545c45--
```

Response:

```
HTTP/1.1 201 Created
Content-Length: 74
Content-Type: application/json; charset=UTF-8
Date: Tue, 02 Jun 2015 18:06:28 GMT
Server: spray-can/1.3.3

{
    "id": "69d1d92f-3895-4a7b-880a-82535e9a096e",
    "status": "WorkflowSubmitted"
}
```

### GET /workflow/<id>/status

```
http http://localhost:8000/workflow/69d1d92f-3895-4a7b-880a-82535e9a096e/status
```

Response:
```
HTTP/1.1 200 OK
Content-Length: 74
Content-Type: application/json; charset=UTF-8
Date: Tue, 02 Jun 2015 18:06:56 GMT
Server: spray-can/1.3.3

{
    "id": "69d1d92f-3895-4a7b-880a-82535e9a096e",
    "status": "WorkflowSucceeded"
}

### GET /workflow/<id>/outputs

```
http http://localhost:8000/workflow/69d1d92f-3895-4a7b-880a-82535e9a096e/outputs
```

Response:
```
HTTP/1.1 200 OK
Content-Length: 250
Content-Type: application/json; charset=UTF-8
Date: Tue, 02 Jun 2015 18:07:04 GMT
Server: spray-can/1.3.3

{
    "id": "69d1d92f-3895-4a7b-880a-82535e9a096e",
    "outputs": {
        "three_step.cgrep.count": "WdlInteger(11)",
        "three_step.ps.procs": "WdlFile(/var/folders/kg/c7vgxnn902lc3qvc2z2g81s89xhzdz/T/stdout4640266685582409635.tmp)",
        "three_step.wc.count": "WdlInteger(11)"
    }
}
```
