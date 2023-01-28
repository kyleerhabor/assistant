# Assistant

A Discord bot to assist my everyday needs.

[Invite (Private)](https://discord.com/api/oauth2/authorize?client_id=856158596344709130&permissions=123904&scope=bot%20applications.commands)

## Rationale

The Discord bot space is oversaturated—why another one?

Discord bots are cool. Before slash commands, a bot would read, parse, validate, and process messages as commands when they were sent. It was very flexible, but lacked a standard for handling commands. While many bots adopted the same principles, the lack of a system bred individualism, resulting in prefixes, syntaxes, help commands, and niche essentials differing between one another. Consequently, users were forced to learn each bot's language and vocabulary.

With the introduction of interactions, many bots replaced their systems with slash commands but retained the gimmicks and complexities of their former implementations. Commands are often conflated with non-essential information or details the user did not ask for. For example, while an `/avatar` command could *just* display a user's avatar, many bots add supplemental details, such as by labeling who the avatar belongs to (e.g. "Avatar for Klay#7270"), linking to additional formats and sizes, stating who ran the command, providing an option to retrieve the guild icon, and more in spite of it all creating needless complexity for the user. It may be easy to dismiss such concerns by encouraging users to ignore the surplus of information, but when whole systems are designed under this principle, it begins to hurt the experience as a user. Instead, I prefer to design commands around simplicity and options for details. In the past, this meant using named parameters (with dashes usually, such as `-f`, `--flag`, `--flag=value`, and `--flag="some value"`), resolvers (or types), comprehensive documentation (consistent and accessible via a help command, wiki, website, etc.), and more, which were notoriously difficult for users to comprehend. Today, however, slash commands trivialize such problems and make dreams realizable.

I wanted a bot capable of providing a simple interface and experience, but couldn't find one I liked. In protest, Assistant was created to address those issues. The adoption of interactions contributes to a consistent system all users are familiar with and can benefit from (no help command!). Commands are designed to be minimalistic by only displaying the information essential to users, with options for configuration and convenient defaults where they make sense. When simplicity is not an option for a feature, it will be rejected or developed with a compromise. For example, while a [pick command](https://github.com/KyleErhabor/assistant/issues/17) would be useful, Discord does not support variadic arguments, and, hence, it hasn't been implemented. In comparison, the `/animanga` command only accepts an ID for its `query` parameter since many anime and manga share identical titles. To alleviate this burden, the command supports autocomplete, mapping titles to IDs. Assistant's principles are based on what makes traditional and slash commands appealing to developers and users so I hope you enjoy its design philosophy.

*- Klay#7270*

## Installing

Assistant is a privately hosted bot, so there is no public bot to invite. Instead, you'll need to install and invite your own instance.

## License

Licensed under the [Cooperative Software License](./LICENSE).
