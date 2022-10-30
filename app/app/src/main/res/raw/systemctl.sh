#!/bin/bash

fork() {
  (setsid "$@" &);
}

get_service()
{
	service_name=$1
	service=/etc/systemd/system/${service_name}.service
	if [ ! -f "$service" ]; then
	  service=/etc/systemd/system/${service_name}.service
	fi
	if [ ! -f "$service" ]; then
	  service=/etc/systemd/system/${service_name}.service
	fi
	if [ ! -f "$service" ]; then
	  service=${service_name}.service
	fi

	if [ ! -f "$service" ]; then
	  initd=/etc/init.d/${service_name}

	  if [ ! -f "$initd" ]; then
	    echo "SERVICE: $1 not found"
	    exit 1
	  fi
	fi
	echo $service
}

get_systemd_cfg()
{
  echo $(sed -n -e '/\[Service\]/, /\[/ p' "$service" | grep $1 | sed -e "s/${1}[ \\t]*=[ \\t]*//g")
}

get_pid()
{
  service_name=$1
  PID_FILE=/var/run/${service_name}_sysctl.pid
  if [ -f $PID_FILE ]; then
    PID=$(cat /var/run/${service_name}_sysctl.pid 2>/dev/null || true)
  else
    PID=INVALID
  fi
  if [ "$PID" == "KILLED" ]; then
    PID=INVALID
  fi
  echo $PID
}

run_service()
{
  echo "SERV: $service"
  cmd=$(get_systemd_cfg ExecStart)
  env=$(get_systemd_cfg EnvironmentFile)
  cwd=$(get_systemd_cfg WorkingDirectory)
  cwd=${cwd:-$(pwd)}

  # if [ -f ${env} ]; then
  #   export $(grep -v '^#' .env | xargs -d '\n')
  # fi
  if [[ "$env" != "" ]] && [ -f ${env} ]; then
    set -a
    source ${env}
    set +a
  fi

  cd $cwd;
  cmd_line=$(eval echo $cmd)
  echo "CMD: $cmd_line"
  SERVICE="$servive_name" $cmd_line &
  RES=$?
  PID=$!
  #echo "PID: $PID"
  #echo "CMD: $cmd"
  # if [ "$RES" == "0" ]; then
  if ps -p $PID >& /dev/null; then
    echo "$PID" > /var/run/${service_name}_sysctl.pid
  else
    echo "KILLED" > /var/run/${service_name}_sysctl.pid
  fi
}

stop_service()
{
  pid=$(get_pid $service_name)
  kill -15 ${pid} >& /dev/null || echo "Failed to stop: $pid"
  kill -9 ${pid} >& /dev/null || true

  if ps -p $pid >& /dev/null; then
    echo "Failed to stop ${service_name}!"
  else
    echo "KILLED" > /var/run/${service_name}_sysctl.pid
    echo "SERVICE ${service_name} stopped."
  fi
}

status()
{
  pid=$(get_pid $service_name)
  desc=$(sed -n -e '/\[Unit\]/, /\[/ p' "$service" | grep Description | sed -e 's/Description[ \t]*=[ \t]*//g')
  cmd=$(sed -n -e '/\[Service\]/, /\[/ p' "$service" | grep ExecStart | sed -e 's/ExecStart[ \t]*=[ \t]*//g')

  echo "service: ${service_name}.service - ${desc}"
  echo "  Loaded: loaded (/etc/systemd/system/${service_name}.service; enabled; vendor preset: enabled)"
  if [ "$pid" == "INVALID" ]; then
    echo "  Active: inactive (dead)"
  else
    # if ps -p ${pid} 2>/dev/null; then
    if ps -p $pid >& /dev/null; then
      echo "  Active: active (running)"
      echo "Main PID: ${pid} (${service_name})"
    else
      if [ "$pid" == "KILLED" ]; then
        echo "  Active: inactive (dead)"
      else
        echo "  Active: failed (Result: exit-code)"
      fi
    fi
  fi
}


if [ "$1" = "list-units" ]; then
  for file in `ls /etc/systemd/system/*.service`; do echo "$(basename -- ${file})  loaded  active  dead"; done
  for file in `ls /etc/init.d/*`; do echo "$(basename -- ${file}).service  loaded  active  dead"; done
  exit 0
elif [ "$1" = "show" ]; then
  for var in "$@"; do
    shift
    if [[ "$var" != "--value" ]]; then continue; fi

    for service_name in "$@"; do
      # service=$(get_service service_name)
      pid=$(get_pid $service_name)
      if [ "$pid" == "INVALID" ]; then
        echo "$service_name.service    active    dead"
      else
        echo "$service_name.service    active    running"
      fi
    done
    exit 0
  done
fi

# if [ "$2" -eq "$2" ]; then
re='^[0-9]+$'
if [[ $2 =~ $re ]]; then
  #service_name=$(ps --no-headers -p $2` | awk '/SERVICE={ print $NF }')
  service_name=$(grep "$2" /var/run/*sysctl.pid | head -1 | sed -e 's/\/var\/run\///g' | cut -d ':' -f 1 | sed -e 's/_sysctl\.pid//g')
else
  service_name=$(echo "$2" | cut -f 1 -d '.')
fi
service=$(get_service $service_name)

# echo "! SERVICE: ${service} -- ${service_name}"

# set -x
set -e

if [ "$initd" = "" ]; then
  if [ "$1" = "start" ]; then
    run_service
  elif [ "$1" = "stop" ]; then
    stop_service
  elif [ "$1" = "restart" ]; then
    stop_service || true
    run_service
  elif [ "$1" = "status" ]; then
    status
  else
    echo "Invalid operation '$1'."
    exit 1
  fi
else
  if [ "$1" = "status" ]; then
    echo "  Loaded: loaded (/etc/init.d/${service_name}; enabled; vendor preset: enabled)"
    if $initd status | grep -q ' is running' ; then
      echo "  Active: active (running)"
    else
      echo "  Active: inactive (dead)"
    fi
  else
    $initd $1
  fi
fi
