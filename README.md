# Assistant

![Banner](./banner.png)

A Discord bot to assist my everyday needs.

[Invite (Private)](https://discord.com/api/oauth2/authorize?client_id=856158596344709130&permissions=8192&scope=applications.commands%20bot)

## Rationale

The Discord bot space is oversaturated—why another one?

Discord bots are cool. Before application commands, a bot would read sent messages, validate and conform it, and run a
command the bot defined. However, there was no formally standardized way to issue or develop commands. While bots were
flexible, the lack of a system breeded individualism, requiring them to adopt their own prefixes (for conflict
resolution against other bots), help commands, syntax, and niches for users to comprehend.

With application commands, many bots are migrating their traditional commands to use interactions, but are
retaining gimmicks and complexity that places burden on the user. For example, an `/avatar` command may display a 
user's avatar with links to additional formats (PNG, JPEG, WebP, and GIF) and sizes, but this is often unnecessary as 
it adds more information than the user needs. The user just wanted *some* image—not all of them in all their forms. The 
command could've been written to use optional parameters, but traditional commands using positional or named arguments 
(with dashes usually) are often difficult and confusing, resulting in more consults to the help command. These 
anti-patterns are common among bots of all sizes.

I wanted a bot capable of providing a simple interface and experience, but couldn't find one I liked. In protest,
Assistant was created to address those issues—first class support for application commands (subcommands, groups, typed
parameters, autocompletion, etc.) while only showing the details that matter. Assistant's principles are based on what
makes traditional commands and interactions appealing to users and I hope you enjoy it.

*- Klay#7753*

## Installing

You can download the project from the [Releases](https://github.com/KyleErhabor/assistant/releases/latest) page.
[Clojure](https://clojure.org/guides/getting_started) is required to build it. In the examples, the commands should be 
run in the top-level directory of the project. `<token>` refers to the [Discord bot token](https://discord.com/developers/applications) the bot will use to authenticate.

### Clojure

If you'd prefer to use Clojure directly, you can run the following command:

```sh
clojure -M -m assistant.core <token>
```

### Java

Run `clj -T:build uber` build the project. This may take a while to complete (e.g. a minute). Once finished, run the
following command to start the bot:
```sh
java -jar target/assistant-<version>-standalone.jar <token>
```

`<version>` must be the version of the project built, which can be found in the `target` directory.

## License

Copyright © 2021 Kyle Erhabor

Licensed under the Apache License, Version 2.0 (the "License"); you may not use this software except in compliance with
the License. You may obtain a copy of the License in [LICENSE.txt](./LICENSE.txt).

Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on an
"AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the specific
language governing permissions and limitations under the License.
