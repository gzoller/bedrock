# bedrock
Starting point for REST service

This branch demonstrates the (apprently) malfunctionality CLI capability in ZIO HTTP when using HttpCliApp.fromEndpoints.

I've build a simple REST service, mainly from the ZIO HTTP docs with minor tweaks. Then I made a CLI client, again from the ZIO HTTP docs.

Swagger has been enabled.

I build a package and run assembly, then from the OS command line run the server like this:

```
java -jar target/scala-3.5.2/bedrock-assembly-0.1.0-SNAPSHOT.jar
```

At this point I can go to ```http://localhost:8080/docs/openapi``` and verify the Swagger interface works as expected. I can also successfully curl both endpoints.

Then I run the CLI client like this to see the greeting message:

```
java -cp target/scala-3.5.2/bedrock-assembly-0.1.0-SNAPSHOT.jar com.me.TestCliApp
```

And see output:

```
    __                __                                        __
   / /_  ____  ____  / /_______      ________  ____ ___________/ /_
  / __ \/ __ \/ __ \/ //_/ ___/_____/ ___/ _ \/ __ `/ ___/ ___/ __ \
 / /_/ / /_/ / /_/ / ,< (__  )_____(__  )  __/ /_/ / /  / /__/ / / /
/_.___/\____/\____/_/|_/____/     /____/\___/\__,_/_/   \___/_/ /_/



books-search v0.0.1 -- Books search CLI

USAGE

  $ books-search <command>

COMMANDS

  - get-books -q text  Endpoint to query books based on a search query



  - get-hello

Copyright 2024
```

So far, so good. Ok, now I try to call an API.

## Test 1

```
java -cp target/scala-3.5.2/bedrock-assembly-0.1.0-SNAPSHOT.jar com.me.TestCliApp books-search get-hello
```

And see error:

```
Missing command name: get-hello

timestamp=2024-11-30T23:00:28.987295Z level=ERROR thread=#zio-fiber-480252303 message="" cause="Exception in thread "zio-fiber-1215190538" zio.cli.ValidationError: ValidationError(CommandMismatch,Paragraph(Text(Missing command name: get-hello)))
	at zio.cli.CliApp.CliAppImpl.run(CliApp.scala:119)
	at zio.cli.ZIOCli.run(ZIOCli.scala:13)"
```
I can try different permutations, for example adding '-' before get-hello. 

## Test 2
If I try the get-books command: 

```
java -cp target/scala-3.5.2/bedrock-assembly-0.1.0-SNAPSHOT.jar com.me.TestCliApp books-search get-books -q scala
```

I get:
```
Missing command name: get-hello

timestamp=2024-11-30T23:02:13.703184Z level=ERROR thread=#zio-fiber-176227677 message="" cause="Exception in thread "zio-fiber-1024686448" zio.cli.ValidationError: ValidationError(CommandMismatch,Paragraph(Text(Missing command name: get-hello)))
	at zio.cli.CliApp.CliAppImpl.run(CliApp.scala:119)
	at zio.cli.ZIOCli.run(ZIOCli.scala:13)"
```
Strange, eh? Complains about get-hello even though I gave it get-books.

## Test 3
Now... I'll try cliStyle = false in fromEndpoints(). Running everything as before, here's the greeting message:
```
    __                __                                        __
   / /_  ____  ____  / /_______      ________  ____ ___________/ /_
  / __ \/ __ \/ __ \/ //_/ ___/_____/ ___/ _ \/ __ `/ ___/ ___/ __ \
 / /_/ / /_/ / /_/ / ,< (__  )_____(__  )  __/ /_/ / /  / /__/ / / /
/_.___/\____/\____/_/|_/____/     /____/\___/\__,_/_/   \___/_/ /_/



books-search v0.0.1 -- Books search CLI

USAGE

  $ books-search <command>

COMMANDS

  - get /books?q -q text  Endpoint to query books based on a search query



  - get /hello

Copyright 2024
```

Take a look at that wild query param spec for 'q' there. What is that? What is an example usage of that? Looks like two concepts got mangled together there.
The hello endpoint didn't work here either.

## Extra
As an extra note, there are 3 lines separating each API call in the COMMANDS section. That's visually excessive. 1 empty line between each API is sufficient and cleaner.
