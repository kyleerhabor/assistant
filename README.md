# Assistant

![Banner](./banner.png)

A Discord bot to assist my everyday needs.

[Invite (Private)](https://discord.com/api/oauth2/authorize?client_id=856158596344709130&permissions=8192&scope=applications.commands%20bot)

## Rationale

The Discord bot space is over-saturated—why another one?

Discord bots are cool. Before application commands, a bot would read sent messages, validate and conform it, and run a
command the bot defined. However, there was no formally standardized way to issue or develop commands. While bots were
flexible, the lack of a system breeded individualism, requiring them to adopt their own prefixes (for conflict
resolution against other bots), help commands, syntax, and niches for users to comprehend.

With application commands, many bots are migrating their traditional commands to use interactions, but are
retaining gimmicks and complexity that places burden on the user. For example, an `/avatar` command may display a 
user's avatar with additional formats (PNG, JPEG, WebP, and GIF) and sizes, but this is often unnecessary, as it adds 
more information than the user needed. The user just wanted *some* image—not all of them in all their forms. The 
command could've been written to use optional parameters, but traditional commands using positional or named arguments 
(with dashes usually) were often difficult and confusing, resulting in more consults to the help command. This
anti-pattern is common among bots of all sizes.

A fundamental goal of Assistant is to provide a simple experience with a standardized interface. This meant first-class
support for application commands (subcommands, groups, typed parameters, autocompletion, etc.) while rejecting commands
and details the user doesn't need. I hope you find Assistant's view of simplicity easy and fun to use.

## Installing

[Clojure](https://clojure.org/guides/getting_started) is required to build Assistant. After Clojure has been installed,
[download the project](https://github.com/KyleErhabor/assistant/releases/latest) and run `clj -T:build uber` to build
it. This may take a while to complete (e.g. a minute).

### Running

In the top-level directory of the project, run:
```sh
java -jar target/assistant-<version>-standalone.jar <token>
```

`<version>` must be the version of the project built, which can be found in the `target` directory. `<token>` must be
the [Discord bot token](https://discord.com/developers/applications) to authenticate with.

## License

Copyright © 2021 Kyle Erhabor

Licensed under the Apache License, Version 2.0 (the "License"); you may not use this software except in compliance with
the License. You may obtain a copy of the License in [LICENSE.txt](./LICENSE.txt).

Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on an
"AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the specific
language governing permissions and limitations under the License.
