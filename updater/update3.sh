#!/bin/bash
#Database downloader script

echo Downloading database...

cd $1
mkdir tmpdir
cd tmpdir
rm -rfv "$5".tar.gz
curl --retry 100 https://s3.amazonaws.com/updates.apollowallet.org/database/$5.tar.gz -o $5.tar.gz
tar -zxvf $5.tar.gz


CONFIGDIR=conf

if [ $5 == 'a2e9b9']
    CONFIGDIR=conf-tn1
fi

if [ $5 == '2f2b61']
    CONFIGDIR=conf-tn2
fi

if [ $3 == 'true' ]
then
    rm -rfv ~/.apl-blockchain/apl-blockchain-db/$5
    cp -rfv $1/tmpdir/$5 ~/.apl-blockchain/apl-blockchain-db/

else
    if [ -f $1/$CONFIGDIR/apl-blockchain.properties ]
    then
	if [ 1 == $(cat $1/$CONFIGDIR/apl-blockchain.properties | grep customDbDir | grep -v "#" | wc -l) ]
	then 
    	    cd $1
    	    cd $(cat $1/$CONFIGDIR/apl-blockchain.properties | grep customDbDir | cut -f2 -d'=')
    	    rm -rfv $5
    	    cp -rfv $1/tmpdir/$5 .
	else
	    cd $1/apl-blockchain-db/
	    rm -rfv $5
    	    cp -rfv $1/tmpdir/$5 .
	fi
	
    fi
    rm -rfv $/tmpdir
fi

rm -rfv $/tmpdir