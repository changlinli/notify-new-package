# Notify New Package

The source code behind [https://notifynewpackage.com](https://notifynewpackage.com).

A web service built on top of
[release-monitoring.org](https://release-monitoring.org/). This aims to be a
simple way to subscribe to email updates when a new version of your favorite
piece of software is released. 

For example, you might want to know when the latest version of `gcc` is
released, or when a new version of `glibc` is released. You might be a package
maintainer that needs to package upstream packages and don't want to miss out on
a new version, or maybe you're a developer who wants to know when the newest
version of a dependency has been released. Or maybe you're just a user of
software who'd like to know when a new version has come out.

## Why do I see .pem files committed? Is this a security vulnerability?

These are publicly made available by Fedora to allow for connecting to their
public instance of AMQP brokers. So no, they're meant to be public and are
simply included here in this repo for convenience.
