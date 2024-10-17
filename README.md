# VelocityPteroPower
![Static Badge](https://img.shields.io/badge/Velocity-green) <br>
[![forthebadge](https://forthebadge.com/images/badges/works-on-my-machine.svg)](https://forthebadge.com)

<p align="center">
    <a href="https://discord.pluginz.dev">
        <img src="https://i.imgur.com/JgDt1Fl.png" width="300">
    </a>
    <br>
    <i>Please join the Discord if you have questions!</i>
</p>

This is a Plugin for Velocity Servers which can dynamically start and stop servers that are managed with the [Pterodactyl Server Panel](https://pterodactyl.io/)

This Project is a port of the [BungeePteroPower](https://github.com/Kamesuta/BungeePteroPower)
## Features
- start a Server manually with `/ptero start`
- stop a Server manually with `/ptero stop`
- restart a server manually with `/ptero restart`
- reload the config using `/ptero reload`
<br><br>
- The plugin will automatically start a Server that a player is trying to connect (if the server is configured in the config file)
-  The plugin will automatically schedule a server shutdown if a server is empty for a certain amount of time (time is configurable
   in the config file)
  - scheduled shutdowns will be canceled if a player joins the server that is scheduled to be shutdown
- The plugin will never exceed the RateLimit of the Panel API
- automatic checks for new updates of the plugin

## Permissions
- `ptero.start` Permission for the `/ptero start` command
- `ptero.stop`Permission for the `/ptero stop` command
- `ptero.restart` Permission for the `/ptero restart` command
- `ptero.reload` Permission for the `/ptero reload` command
## Installation 
To install the Plugin on your Velocity Server put the `.jar` in your plugin folder and `restart/start` your server.

## Example config
```yml
################################
#      VelocityPteroPower      #
#         by TubYoub           #
################################

# Version of the configuration file
fileversion: '1'

checkUpdate: true

# How many Threads should be used to check the server status
# The more threads the more requests can be handled at the same time
# more threads = more resources used
# restart the server so changes take effect
# default: 10
apiThreads: 10

# print updated Rate Limit from the API response to console
# default: false
printRateLimit: true

# How long the ping to the server lasts, to check if its is online, until it times out (in milliseconds)
# default: 1000
pingTimeout: 1000

# This is used to check the server status to transfer players after the server starts
startupJoin:
  
  # Once the server is pingable, wait the specified amount of seconds before sending the player to the server
  # This is useful to wait for plugins like Luckperms to fully load
  # If you set it to 0, the player will be connected as soon as the server is pingable
  joinDelay: 5

# Pterodactyl configuration
pterodactyl:
  # The URL of your pterodactyl panel
  # If you use Cloudflare Tunnel, you need to allow the ip in the bypass setting.
  url: http://192.168.178.74:2462/
  # The client api key of your pterodactyl panel. It starts with "ptlc_".
  # You can find the client api key in the "API Credentials" tab of the "Account" page.
  apiKey: ptlc_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa

# Per server configuration
servers:
  #server name
  hub:
    
    # Pterodactyl server ID
    # You can find the Pterodactyl server ID in the URL of the server page.
    # For example, if the URL is https://panel.example.com/server/1234abcd, the server ID is 1234abcd.

    id: 1234abcd
    
    # The time in seconds to stop the server after the last player leaves.
    # If you don't want to stop the server automatically, set it to -1.
    # If you set it to 0, the server will be stopped immediately after the last player leaves.

    timeout: -1
  test:
    id: abcd1234
    timeout: 5
  mc-purpur-1:
    id: ab12cd34
    timeout: 180
  mcforge1:
    id: 1111abcd
    timeout: -1

```

## Support

If you have any issues, or suggestions please open a [issue](https://github.com/TubYoub/VelocityPteroPower/issues/new) or join the discord

## Contributing

Join the [discord](https://discord.pluginz.dev) and talk with me about your idea or just do it yourself and open a pull request.

## License

This project is licensed under the [MIT License](LICENSE).

[![forthebadge](https://forthebadge.com/images/badges/powered-by-black-magic.svg)](https://forthebadge.com)
