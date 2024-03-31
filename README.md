# VelocityPteroPower
![Static Badge](https://img.shields.io/badge/Velocity-green) <br>
[![forthebadge](https://forthebadge.com/images/badges/works-on-my-machine.svg)](https://forthebadge.com)

This is a Plugin for Velocity Servers which can dynamically start and stop servers that are managed with the [Pterodactyl Server Panel](https://pterodactyl.io/)

This Project is a port of the [BungeePteroPower](https://github.com/Kamesuta/BungeePteroPower)
## Features
- Start a Server manually with `/ptero start`
- Stop a Server manually with `/ptero stop`
- Reload the config using `/ptero reload`
<br><br>
- The plugin will automaticly start a Server that a player is trying to connect (if the server is configured in the config file)

## Permissions
- `ptero.start` Permission for the `/ptero start` command
- `ptero.stop`Permission for the `/ptero stop` command
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
  fitnacraft1:
    id: ab12cd34
    timeout: 180
  mcforge1:
    id: 1111abcd
    timeout: -1

```

## Support

If you have any issues, please report. And if u have any suggestions, feel free to open an issue.

## Contributing

I currently have no plans to get co-contributer's on this project, but if you have any suggestions, feel free to open an issue


## License

This project is licensed under the [MIT License](LICENSE).

[![forthebadge](https://forthebadge.com/images/badges/powered-by-black-magic.svg)](https://forthebadge.com)
