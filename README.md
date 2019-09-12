# Notify New Package

The source code behind [https://notifynewpackage.com](https://notifynewpackage.com).

A web service built on top of
[release-monitoring.org][0]. This aims to be a
simple way to subscribe to email updates when a new version of your favorite
piece of software is released. 

For example, you might want to know when the latest version of `gcc` is
released, or when a new version of `glibc` is released. You might be a package
maintainer that needs to package upstream packages and don't want to miss out on
a new version, or maybe you're a developer who wants to know when the newest
version of a dependency has been released. Or maybe you're just a user of
software who'd like to know when a new version has come out.

## Why do I see .pem and .pfx files committed? Is this a security vulnerability?

These are publicly made available by Fedora to allow for connecting to their
public instance of AMQP brokers. So no, they're meant to be public and are
simply included here in this repo for convenience.

## Why do I see a hardcoded PASSWORD in certain places?

This relates to the previous section. The `.pfx` file requires a password upon
creation, but it is generated entirefly from the .pem files that Fedora has
intentionally made public. Therefore its contents are already public and
intentionally hard-coding the password to the `.pfx` file isn't reducing any
security.

## A guide to the code

Behind the simple and ugly almost-HTML-only front-end, there's actually quite a
bit going on under the hood.

There are conceptually four main architectural components.

+ A web server that people interact with (Http4s)
+ An email service that actually takes care of sending emails (SendGrid)
+ A continuously running listener to an AMQP broker for new updates from
  [release-monitoring.org][0] (fs2-rabbit)
+ A database that stores all our data (SQLite)

Unfortunately the code itself still needs a good deal of refactoring, but here's
a guide to what exists so far.

In particular we need to support the following:

+ First and foremost, monitor Fedora's AMQP broker for new package version
  updates provided by [release-monitoring.org][0]
    * Also email subscribed users when a new package version arrives.
    * The code for this lives in
      `com.changlinli.releaseNotification.RabbitMQListener`
+ Import all packages from [release-monitoring.org][0]
    * We need package search, which [release-monitoring.org][0] doesn't provide
      in its API, therefore we need to maintain our own database of all the
      packages that we can search over.
    * The code for this mainly lives in `com.changlinli.releaseNotification.PackageDownloader`
+ Continuously ingest updates to and new packages in [release-monitoring.org][0]
    * We care about when new packages and updates to packages (e.g. changing a
      package name) occur upstream in [release-monitoring.org][0] to keep our
      own database in sync. Note that these updates are different than the
      updates we push to our users, which are purely version changes to a
      package.
    * The code for this also mainly lives in
      `com.changlinli.releaseNotification.RabbitMQListener`
+ Implement an email-based workflow
    * We want to make sure users must confirm their subscriptions and
      unsubscriptions (so that people can't sign emails up for things that the
      email owner doesn't like).
    * We also want to automatically generate unsubscribe links
    * We also need to deal with what happens if users create duplicate
      subscriptions, both for packages that they've already confirmed their
      subscription to and for packages that have not yet been confirmed.
    * Most of this workflow lives in
      `com.changlinli.releaseNotification.WebServer`
+ Periodically clean up the database
    * The only feature here so far is that we periodically delete emails that
      are not subscribed to any package
    * This lives in `com.changlinli.releaseNotification.GarbageDataCollection`

### Building and running the code

This is a vanilla SBT project, so if you have SBT installed, to build and test
`sbt test` will do. To run, simply do `sbt run`. You'll need a SendGrid API key
(and therefore a SendGrid account) in order to actually send emails from this
service.

Here's an example invocation:

```
sbt "run \
    --sendgrid-api-key=YourSendGridAPIKeyHere \
    --public-site-name=localhost \
    --admin-email-address=myemail@example.com \
    --rabbitmq-queue-name=00000000-0000-0000-0000-000000000000"
```

[0]: https://release-monitoring.org/
