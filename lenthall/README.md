[![Build Status](https://travis-ci.org/broadinstitute/lenthall.svg?branch=develop)](https://travis-ci.org/broadinstitute/lenthall?branch=develop)
[![codecov](https://codecov.io/gh/broadinstitute/lenthall/branch/develop/graph/badge.svg)](https://codecov.io/gh/broadinstitute/lenthall)

Lenthall
========

[Cromwell](https://github.com/broadinstitute/cromwell) Common Code

# Mailing List

The [Cromwell Mailing List](https://groups.google.com/a/broadinstitute.org/forum/?hl=en#!forum/cromwell) is cromwell@broadinstitute.org.

If you have any questions, suggestions or support issues please send them to this list. To subscribe you can either join via the link above or send an email to cromwell+subscribe@broadinstitute.org.

# Requirements

The following is the toolchain used for development of Lenthall.  Other versions may work, but these are recommended.

* [Scala 2.12.3](https://www.scala-lang.org/download/2.12.3.html)
* [SBT 0.13.16](https://github.com/sbt/sbt/releases/tag/v0.13.16)
* [Java 8](http://www.oracle.com/technetwork/java/javase/overview/java8-2100321.html)

# Building

`sbt compile` will build a library JAR in `target/scala-2.11/`

Tests are run via `sbt test`.
