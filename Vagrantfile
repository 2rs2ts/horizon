# -*- mode: ruby -*-
# vi: set ft=ruby :

VAGRANTFILE_API_VERSION = "2"

#script to install openjdk 7, scala 2.11.4 and sbt 0.13.7
$script = <<SCRIPT
apt-get update
apt-get -y install git
apt-get -y install openjdk-7-jdk
wget http://www.scala-lang.org/files/archive/scala-2.11.4.deb
dpkg -i scala-2.11.4.deb
apt-get -y update
apt-get -y install scala

wget http://dl.bintray.com/sbt/debian/sbt-0.13.7.deb
dpkg -i sbt-0.13.7.deb
apt-get -y update
apt-get -y install sbt

#SBT setup for releasing

#export SBT_SONATYPE_CREDENTIALS=<<EOF
#credentials += Credentials("Sonatype Nexus Repository Manager", "oss.sonatype.org", "<your username>", "<your password>")
#EOF

#export SBT_BASE=/home/vagrant/.sbt/0.13

#touch $SBT_BASE/sonatype.sbt
#echo $SBT_SONATYPE_CREDENTIALS > $SBT_BASE/sonatype.sbt
#chmod 777 $SBT_BASE/sonatype.sbt

SCRIPT

Vagrant.configure(VAGRANTFILE_API_VERSION) do |config|
  config.vm.box = "hashicorp/precise64"
  config.vm.provider "virtualbox" do |v|
      v.memory = 2048
      v.customize ["modifyvm", :id, "--cpuexecutioncap", "50"]
  end
  config.vm.provision "shell", inline: $script
end
