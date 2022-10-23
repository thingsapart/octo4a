#!/bin/sh
echo "virtualenv $@"

set -x
set -e

PY=python3
if [ "$1" = "-p" ] ; then
  PY=`which $2`
  PYV=`basename $2`
  shift
  shift
fi

mkdir -p $1/bin
cd $1/bin

ln -s "$PY" $PYV
ln -s "$PY" python

if [ "$PYV" = "python3" ]; then
  PIP=pip3
else
  PIP=pip
fi

cat <<EOF >$PIP
#!/bin/bash
sudo `which $PIP` \$@
EOF
chmod a+x $PIP

if [ "$PYV" = "python3" ]; then
  ln -s pip3 pip
fi
echo "created virtual environment CPython`./python --version` in 12345ms"