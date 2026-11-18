NiceRTP 1.0.0  -  Paper/Bukkit 1.21.11, Java 21

BUILD
  Option A (PC / Termux):  mvn clean package
                           -> jar is in target/NiceRTP-1.0.0.jar
  Option B (no PC):        push this folder to a GitHub repo.
                           The included workflow builds it; download the jar
                           from the Actions tab -> latest run -> Artifacts.

INSTALL
  Put the jar in your server's plugins/ folder and restart.
  Edit plugins/NiceRTP/config.yml, then /nrtp reload.

COMMANDS
  /rtp [overworld|nether|end|queue|back|cancel]
  /nicertp (alias /nrtp)  reload | config | --forcertp <player> [world]
