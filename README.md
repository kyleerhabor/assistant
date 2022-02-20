# Assistant

![Banner](./banner.png)

A Discord bot to assist my everyday needs.

[Invite (Private)](https://discord.com/api/oauth2/authorize?client_id=856158596344709130&permissions=9216&scope=bot%20applications.commands)

## Rationale

The Discord bot space is oversaturated—why another one?

Discord bots are cool. Before slash commands, a bot would read, parse, validate, and process messages as they were sent.
It was very flexible, but lacked a standard for handling commands. While many bots adopted the same principles, the lack
of a system bred individualism, resulting in prefixes, syntaxes, help commands, and niche essentials differing between
one another. Consequently, users were forced to learn each bot's language and vocabulary.

With the introduction of interactions, many bots replaced their systems with slash commands but retained the gimmicks
and complexities of their former implementations. Commands are often conflated with non-essential information or details
the user did not request for. For example, while the `/avatar` command is meant to display a user's avatar, bots often
include extra details, such as who's avatar it is (e.g. "Avatar for Klay#0427"), links to additional formats and sizes,
who ran the command, the guild icon, and more, despite none of it being requested or essential. While many developers
believe that the information can be useful and merely ignored, I believe users should receive the information they
need and request the extra they want. Previously, this meant using named parameters
(with dashes usually, like `-f`, `--flag`, `--flag=value`, and `--flag="some value"`), resolvers (or types),
documentation (help command, wiki, website, etc.), and more, which was notoriously difficult for users to comprehend.
Today, however, there's no reason why this can't be a reality.

<!-- "wanted" or "yearned for"? -->
I yearned for a bot capable of providing a simple interface and experience, but couldn't find one I liked. In protest,
Assistant was created to address those issues. The adoption of interactions contributes to a consistent system all users
are familiar with and can benefit from (no help command!). Commands are designed to be minimalistic by only displaying
the information essential to users, with options for configuration and convenient defaults where they make sense. When
it's not possible to be simple, the project will refuse a feature or compromise where possible. For example, while a
[pick command](https://github.com/KyleErhabor/assistant/issues/17) would be useful, Discord has no support for variadic
arguments, therefore it hasn't been implemented. The `/animanga` command, on the other hand, only accepts an ID for its
`query` parameter due to anime and manga sharing identical titles. To alleviate the burden, the command supports
autocomplete and maps titles to their IDs. Assistant's principles are based on what makes traditional commands and slash
commands appealing to developers and users and I hope you enjoy its design philosophy.

*- Klay#0427*

## Installing

Assistant is a privately hosted bot, so there is no public bot to invite. Instead, you'll need to install and invite
your own instance.

### Invite

To invite your own instance of Assistant, use the following invite link, replacing `CLIENT_ID` with your bot's ID:
`https://discord.com/api/oauth2/authorize?client_id=CLIENT_ID&permissions=9216&scope=bot%20applications.commands`

### Download

You can download the project with `git clone https://github.com/KyleErhabor/assistant` or from the
[releases](https://github.com/KyleErhabor/assistant/releases) tab on GitHub. Downloading with `git clone` is useful when
you'd like the latest features, but don't mind the potential bugs it may have.

### Configuration

Assistant accepts a list of files when running that will be parsed as [edn](https://github.com/edn-format/edn) and used
throughout the application, giving you the flexibility to host multiple, ad hoc configurations
(development, testing, production, etc.)

#### `:bot/token`

**Required.** The token of the bot to use when logging in to Discord.

#### `:bot/commands`

A map of global and guild commands. Global commands are keyed under `:global` while guild commands are keyed by the
guild they're associated with. The value is a map representing the type of the command
(currently accepting `:chat-input`, `:user`, or `:message`). Finally, the value is a map of the command names to their
individual configurations. For example, the following would:
- Set the global `purge` slash command's success message timeout before deletion to 3 seconds.
- Perform the following for the guild with the ID of `939382862401110058`,
  - Set the `report` user command's destination channel ID to `940331535196901386`.
  - Set the `translate` message command's supported languages to English (United States and Great Britain) and Chinese
  (China and Taiwan).

Note that the `report` and `translate` commands don't actually exist.
```clj
{:bot/commands {:global {:chat-input {:purge {:timeout 3000}}}
                "939382862401110058" {:user {:report {:channel-id "940331535196901386"}}
                                      :message {:translate {:languages [:en-US :en-GB :zh-CN :zh-TW]}}}}}
```

### Running

To start Assistant, run:

```sh
clojure -M -m assistant.core ...
```
The `...` represents the list of [configuration files](#configuration) to use. For example, the following would read,
parse, and merge `config.edn` and `secrets.edn` from left to right, with the right taking precedence in the event of
duplication.
```sh
clojure -M -m assistant.core config.edn secrets.edn
```

## License

Copyright © 2021-2022 Kyle Erhabor

Licensed under the Apache License, Version 2.0 (the "License"); you may not use this software except in compliance with
the License. You may obtain a copy of the License in [LICENSE](./LICENSE).

Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on an
"AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the specific
language governing permissions and limitations under the License.
