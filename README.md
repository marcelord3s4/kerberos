This is a git repository on github.

To get it use

git clone https://github.com/clhedrick/kerberos.git

I'm keeping a copy on /staff/src for consistency with other software, but the primary
repository is github. If you need to change it please ask hedrick to add you to the
repository.

Note that much of this is based on various code by Russ Allbery.

For non-Rutgers people: I think kgetcred and credserv are safe to use. I expect to change them over the next few months, but if you're happy with what they do, I think it's OK. I haven't used renewd enough yet to feel safe in using it in production. I'd be happy to get feedback about it.

# Goals

1. Secure NFS. Make sure users can't install systems at an IP where we've exported a file system and then access all files.

Also, because Kerberized NFS checks on a per-user basis, even if someone becomes root on a machine, they can't compromise user files. A user should only be exposed on a system where they are actually logged in, or on which they run cron jobs. There's not much better I can do.

This should make it safe to allow faculty to mount our file systems on machines they run.

2. Secure ssh. We allow ssh without passwords based on IP address. This has obvious problems if users fake an IP address or become root. A user must now have an actual ticket. We can still restrict which groups of systems can access others, probably used netgroup-based configuration in sshd_config.

# Challenges

1. Make Kerberos transparent. I'm dealing with this by supporting all ways into a machine through pam, and by having a daemon that will keep their credentials renewed as long as they have processes.

2. Support for multiple machine types. This is hard. Without two-factor, Linux, Mac, and probably Windows (though I haven't tried) can support it. Two factor uses recent features. Currently the key part, kgetcred, works on Centos 5 - 7, and OS X (using the Macports version of Kerberos).

Suggested configuration:

sssd for authentication for Centos 7, the vendor's pam_krb5 on other systems. This will handle most users.

For users with 2FA, they can log into a Centos 7 system, then ssh to an older machine. Credentials obtained with 2FA can still be forwarded to older systems.

For older systems we could also use pam_ldap after pam_krb5. That would let 2FA users login.
The only disadvantage to ldap is that it won't give users Kerberos tickets.

# Design issues

Policies need to be chosen carefully to support our goals. In particular, Kerberos policies need to be adjusted. I'm using a nearly infinite renew time, to support very long sessions. However credentials should probably be set to expire fairly quickly (in /etc/krb5.conf.) Currently for testing it's set to a day, but in production it should probably be an hour. The issue is that once you access a file over NFS, access is cached. The cached permission will last as long as the original ticket was valid. When a user logs out, we'd like his access to expire fairly quickly. Simply destroying the credentials won't cut off NFS access. That only happens when the ticket expires and isn't renewed. Since we're doing automatic renew, a fairly short expiration should be fine.

# Programs 

## renewd

Many users stay logged in more or less forever. We don't want long ticket lifetimes, because that leaves their NFS
connections exposed after they logout. So instead the plan is to expire in 1 hour, but have a daemon that
renews tickets for anyone with a job currently running. The code currently supports the primary cache in KEYRING, and /tmp, because
that's where rpc.gssd looks for tickets for use with NFS.


## credserv and kgetcred

What do we do about users who need to run cron jobs or daemons? Our students often have assignments that require
this. The usual answer is a keytable. But if someone becomes root, they can take anyone's keytable. And having a user's key table permanetly exposes them on all systems.

So instead the plan is to have them register a keytab on a central server (through administative options to kgetcred) and specify the
host where they'll be using a cron job. credserv / kgetcred will generate credentials based on the keytab and
put it on their system. They will be locked to an ip address and not forwardable. This is about the best protection
I can think of.

kgetcred -a also simulates kinit -n. It gets credentials for an unprivileged user. This can be used for kinit -T,
to support two factor kinit.

[As of March 8 the protocol changed, to support administrative operations for setting up credentials and authorizations on the server. This is a substantial change in both program.]

## skinit

Kinit for users with OTP. 

With one time passwords, kinit requires "armor." skinit gets a ticket for anonymous.user, using kgetcred -a,
and uses it to armor
the request. Arguments are just passed on to the main kinit call. 

If your setup supports kinit -n, you might prefer to use that rather than kgetcred -a.

## pam

The issue here is two factor authentication. Freeipa doesn't currently support anonymous credentials with PKINIT.
I've modified Russ Allbery's pam_krb5 to generate a temporary credential file based on /etc/krb5.keytab. That can
be used to armor transactions.

This handles ssh and login, but not things like screen savers. However I really only need pam_krb5 for ways into 
machines, because they have to set up a credentials cache. For a screen saver I can use pam_ldap. The Freeipa
LDAP server support two factor authentication.

I also made a minor patch to avoid an unnecessary second password prompt.

However we are probably going to use sssd rather than this. By the time we roll this out for users, the systems
should be updated to at least Centos 7.
