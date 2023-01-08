# Intellij Angular Inject Into Plugin

![Build](https://github.com/slak44/intellij-angular-inject-into/workflows/Build/badge.svg)
[![Version](https://img.shields.io/jetbrains/plugin/v/20806-angular-inject-into-action.svg)](https://plugins.jetbrains.com/plugin/20806-angular-inject-into-action)
[![Downloads](https://img.shields.io/jetbrains/plugin/d/20806-angular-inject-into-action.svg)](https://plugins.jetbrains.com/plugin/20806-angular-inject-into-action)

<!-- Plugin description -->
The `Angular Inject Into` action makes it easier to inject services or other classes into your Angular components/directives/services.
It lets you search and select what you want to inject, then it automatically adds a private readonly field to the constructor, along with the appropriate import statement.

![Before](./readme-resources/before.png)
![Search](./readme-resources/search_dialog.png)
![After](./readme-resources/after.png)

The plugin correctly handles files with multiple injection targets (eg, a Component and an NgModule in the same file) by
asking which should be used. It also deals with classes without a constructor to inject into by creating one.

To make everything even easier, add a key binding for the action (I use Ctrl-Shift-2).

GitHub: https://github.com/slak44/intellij-angular-inject-into
<!-- Plugin description end -->

## Installation

- Using IDE built-in plugin system:
  
  <kbd>Settings/Preferences</kbd> > <kbd>Plugins</kbd> > <kbd>Marketplace</kbd> > <kbd>Search for "intellij-angular-inject-into"</kbd> >
  <kbd>Install Plugin</kbd>
  
- Manually:

  Download the [latest release](https://github.com/slak44/intellij-angular-inject-into/releases/latest) and install it manually using
  <kbd>Settings/Preferences</kbd> > <kbd>Plugins</kbd> > <kbd>⚙️</kbd> > <kbd>Install plugin from disk...</kbd>


---
Plugin based on the [IntelliJ Platform Plugin Template][template].

[template]: https://github.com/JetBrains/intellij-platform-plugin-template
