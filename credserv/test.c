/* -*- mode: c; c-basic-offset: 4; indent-tabs-mode: nil -*- */

/* 
 * This code is based on the Kerberos sample server, which contains the 
 * following license. There is, however, virtually none of the original 
 * code left here without rewriting.
 *
 * The current code is Copyright 2017, by Rutgers, the State University of
 * New Jersey. It is released under the same license as MIT's, with the obvious
 * replacement of MIT by Rutgers.
 */

/* 
 * Credserv, the service side of kgetcred/credserv. See the man page
 * for specifics of function.
 */

/*
 * Copyright 1990,1991 by the Massachusetts Institute of Technology.
 * All Rights Reserved.
 *
 * Export of this software from the United States of America may
 *   require a specific license from the United States Government.
 *   It is the responsibility of any person or organization contemplating
 *   export to obtain such a license before exporting.
 *
 * WITHIN THAT CONSTRAINT, permission to use, copy, modify, and
 * distribute this software and its documentation for any purpose and
 * without fee is hereby granted, provided that the above copyright
 * notice appear in all copies and that both that copyright notice and
 * this permission notice appear in supporting documentation, and that
 * the name of M.I.T. not be used in advertising or publicity pertaining
 * to distribution of the software without specific, written prior
 * permission.  Furthermore if you modify this software you must label
 * your software as modified software and not distribute it in such a
 * fashion that it might be confused with the original M.I.T. software.
 * M.I.T. makes no representations about the suitability of
 * this software for any purpose.  It is provided "as is" without express
 * or implied warranty.
 */

#include "port-sockets.h"
#include "krb5.h"
#include "com_err.h"

#include <stdio.h>
#include <string.h>
#include <ctype.h>
#include <sys/stat.h>
#include <fcntl.h>
#include <wait.h>
#include <errno.h>
#include <sys/types.h>
#include <sys/socket.h>
#include <netinet/in.h>
#include <netdb.h>
#include <syslog.h>
#include <sys/stat.h>
#include <time.h>
#include <grp.h>
#include <signal.h>
#include <ldap.h>
#include <sasl/sasl.h>

#ifdef HAVE_UNISTD_H
#include <unistd.h>
#endif

#include "sample.h"

extern krb5_deltat krb5_clockskew;

int debug = 0;

// credentaisl is a list of lists. 

struct hostlist {
    char *host;
    char *flags;
    struct hostlist *next;
};

struct princlist {
    char *principal;
    struct hostlist *hosts;
    struct princlist *next;
};

char *admingroup = NULL;


#ifndef GETPEERNAME_ARG3_TYPE
#define GETPEERNAME_ARG3_TYPE int
#endif

#define GENERIC_ERR "Unable to get credentials"
#define NOKEYTAB_ERR "You must register a keytable for this host before you can use this program."

void mylog (int level, const char *format, ...)  __attribute__ ((format (printf, 2, 3)));
void mylog (int level, const char *format, ...) {
    va_list args;
    va_start (args, format);

    if (debug) {
        vprintf(format, args);
        printf("\n");
    } else
        vsyslog(level, format, args);

    va_end(args);
}

/*
 * callback for sasl_interactive_bind
 * taken from IPA source. I don't see any way to have guessed this from 
 * documentation.
 */

static int ldap_sasl_interact(LDAP *ld, unsigned flags, void *priv_data, void *sit)
{
    sasl_interact_t *in = NULL;
    int ret = LDAP_OTHER;
    krb5_principal princ = (krb5_principal)priv_data;
    krb5_context krbctx;
    char *outname = NULL;
    krb5_error_code krberr;

    if (!ld) return LDAP_PARAM_ERROR;

    for (in = sit; in && in->id != SASL_CB_LIST_END; in++) {
        switch(in->id) {
        case SASL_CB_USER:
            krberr = krb5_init_context(&krbctx);

            if (krberr) {
                mylog(LOG_ERR, "Kerberos context initialization failed: %s (%d)", error_message(krberr), krberr);
		in->result = NULL;
                in->len = 0;
                ret = LDAP_LOCAL_ERROR;
                break;
            }

            krberr = krb5_unparse_name(krbctx, princ, &outname);

            if (krberr) {
                mylog(LOG_ERR, "Unable to parse principal: %s (%d)", error_message(krberr), krberr);
                in->result = NULL;
                in->len = 0;
                ret = LDAP_LOCAL_ERROR;
                break;
            }

            in->result = outname;
            in->len = strlen(outname);
            ret = LDAP_SUCCESS;

            krb5_free_context(krbctx);

            break;
        case SASL_CB_GETREALM:
            in->result = princ->realm.data;
            in->len = princ->realm.length;
            ret = LDAP_SUCCESS;
            break;
        default:
            in->result = NULL;
            in->len = 0;
            ret = LDAP_OTHER;
        }
    }
    return ret;
}

int  auth_method    = LDAP_AUTH_SASL;
int desired_version = LDAP_VERSION3;
char* base="cn=accounts,dc=cs,dc=rutgers,dc=edu";
char *grealm = "CS.RUTGERS.EDU";
char *gservice = "credserv";
char *ghostname = "krb1.cs.rutgers.edu";
char *ldapurl = "ldaps://krb1.cs.rutgers.edu,ldaps://krb2.cs.rutgers.edu";
char *targetuser = "hedrick";

LDAP *krb_ldap_open(krb5_context context, char *service, char *hostname, char *realm);

LDAP *krb_ldap_open(krb5_context context, char *service, char *hostname, char *realm) {
    LDAP *ld = NULL;
    int  ret;
    krb5_principal bind_princ = NULL;
    krb5_error_code retval;
    krb5_keytab keytab = NULL;
    krb5_ccache cache = NULL;
    krb5_creds servcreds;
    int havecreds = 0;
    char *putstr = NULL;

    // first we have to set up a credentials file with creds for the credserv/HOST
    // that's used by the GSSAPI authentication

    if ((retval = krb5_kt_resolve(context, "/etc/krb5.keytab", &keytab))) {
        mylog(LOG_ERR, "unable to open /etc/krb5.keytab");
        goto err;
    }

    retval = krb5_build_principal(context, &bind_princ, strlen(realm), realm, service, hostname, NULL);
    if (retval) {
        mylog(LOG_ERR, "failure building kerberos principal for credserv service");
        goto err;
    }

    if ((retval = krb5_get_init_creds_keytab(context, &servcreds, bind_princ, keytab, 0,  NULL, NULL))) {
        mylog(LOG_ERR, "unable to make credentials for service from keytab %s", error_message(retval));
        goto err;
    }
    havecreds = 1;

    // put it in a temporary cache, since we just need it internally
    if ((retval = krb5_cc_new_unique(context, "MEMORY", "/tmp/jjjjj", &cache))) {
        mylog(LOG_ERR, "unable to make credentials file for service %s", error_message(retval));
        goto err;
    }

    if ((retval = krb5_cc_initialize(context, cache, bind_princ))) {
        mylog(LOG_ERR, "unable to initialize credentials file for service %s", error_message(retval));
        goto err;
    }

    if ((retval = krb5_cc_store_cred(context, cache, &servcreds))) {
        mylog(LOG_ERR, "unable to store user credentials in cache %s", error_message(retval));
        goto err;
    }

    asprintf(&putstr, "KRB5CCNAME=MEMORY:%s", krb5_cc_get_name(context, cache));
    putenv(putstr);
    free(putstr);

    // make sure everything is written before we use it
    krb5_cc_close(context, cache);
    cache = NULL;

    // now we have the credentials file set up, do LDAP with GSSAPI

    ldap_initialize (&ld, ldapurl);
    if (ld == NULL) {
        mylog(LOG_ERR, "ldap_initialize failed");
        goto err;
    }

    if (ldap_set_option(ld, LDAP_OPT_PROTOCOL_VERSION, &desired_version) != LDAP_OPT_SUCCESS) {
        mylog(LOG_ERR, "ldap_set_option failed");
        goto err;
    }

    ret = ldap_sasl_interactive_bind_s(ld, NULL, "GSSAPI", NULL, NULL, LDAP_SASL_QUIET, ldap_sasl_interact, bind_princ);
    if (ret != LDAP_SUCCESS) {
        mylog(LOG_ERR, "ldap_sasl_bind_s: %s", ldap_err2string(ret));
    }

    goto ok;

 err:
    if (ld)
        ldap_unbind_ext(ld, NULL, NULL);
    ld = NULL;
 ok:
    if (cache)
      krb5_cc_close(context, cache);
    if (havecreds)
        krb5_free_cred_contents(context, &servcreds);
    if (keytab)
        krb5_kt_close(context, keytab);
    if (bind_princ) 
        krb5_free_principal(context, bind_princ);

    return ld;

}

int main(int argc, char *argv[]) {

    krb5_context context;
    krb5_error_code retval;
    char* filter;
    LDAPMessage* msg;
    BerElement* ber;
    LDAP *ld;
    LDAPMessage *entry;
    char* attr;
    struct berval **vals;
    int i;

    retval = krb5_init_context(&context);
    if (retval) {
        com_err(argv[0], retval, "while initializing krb5");
        exit(1);
    }

    ld = krb_ldap_open(context, gservice, ghostname, grealm);

    /* search from this point */
     
    asprintf(&filter, "(uid=%s)", targetuser);

    if (ldap_search_ext_s(ld, base, LDAP_SCOPE_SUBTREE, filter, NULL, 0, NULL, NULL, NULL, 0, &msg) != LDAP_SUCCESS) {
        perror("ldap_search_s" );
    }
    free(filter);

    for (entry = ldap_first_entry(ld, msg); entry != NULL; entry = ldap_next_entry(ld, entry)) {
        for( attr = ldap_first_attribute(ld, entry, &ber); attr != NULL; attr = ldap_next_attribute(ld, entry, ber)) {
            if ((vals = ldap_get_values_len(ld, entry, attr)) != NULL)  {
                for(i = 0; vals[i] != NULL; i++) {
                    printf("%s: %s\n", attr, vals[i]->bv_val);
                }
                ldap_value_free_len(vals);
            }
            ldap_memfree(attr);
        }
        if (ber)
            ber_free(ber, 0);
        printf("\n");
    }
    ldap_msgfree(msg);

    return 0;
    
}
