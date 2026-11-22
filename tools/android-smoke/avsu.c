/*
 * avsu: a stand-in root manager for the CI emulator (1.4.2-beta3 root way in). NOT for phones.
 *
 * It works the way Magisk / KernelSU su does from an app's point of view: `su` is a client that hands its stdin,
 * stdout and stderr to a root daemon, which runs the shell with them (as root, or as another uid for `su <uid>`) and
 * reports the exit code back. The shell is the daemon's child, outside the app's process group, like Magisk's.
 *
 *   avsu --daemon            started once from `adb root` shell
 *   su [-] [-mm] [uid|root|shell] [-c command]
 * Env of the daemon: AVSU_NO_UID=1 refuses `su <uid>` (a root manager that cannot switch uids), so the app's
 * fallback (service as uid 0) is tested too.
 */
#include <errno.h>
#include <stddef.h>
#include <grp.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <sys/wait.h>
#include <unistd.h>

#define NAME "avsu-ci"
#ifndef SH
#define SH "/system/bin/sh"
#endif
#define PATHV "PATH=/data/local/tmp/avsu-bin:/product/bin:/apex/com.android.runtime/bin:/apex/com.android.art/bin:/system_ext/bin:/system/bin:/system/xbin:/odm/bin:/vendor/bin:/vendor/xbin"

static socklen_t addr(struct sockaddr_un *a) {
    memset(a, 0, sizeof(*a));
    a->sun_family = AF_UNIX;
    memcpy(a->sun_path + 1, NAME, strlen(NAME)); /* abstract */
    return (socklen_t)(offsetof(struct sockaddr_un, sun_path) + 1 + strlen(NAME));
}

static int full(int fd, void *b, size_t n, int wr) {
    char *p = b;
    while (n) {
        ssize_t r = wr ? write(fd, p, n) : read(fd, p, n);
        if (r <= 0) { if (r < 0 && errno == EINTR) continue; return -1; }
        p += r; n -= (size_t)r;
    }
    return 0;
}

static int client(int argc, char **argv) {
    struct sockaddr_un a; socklen_t l = addr(&a);
    int s = socket(AF_UNIX, SOCK_STREAM, 0);
    if (s < 0 || connect(s, (struct sockaddr *)&a, l) < 0) { fprintf(stderr, "su: no root daemon (%s)\n", strerror(errno)); return 1; }
    int fds[3] = {0, 1, 2};
    char cbuf[CMSG_SPACE(sizeof(fds))]; memset(cbuf, 0, sizeof(cbuf));
    int n = argc - 1;
    struct iovec io = {&n, sizeof(n)};
    struct msghdr m = {0};
    m.msg_iov = &io; m.msg_iovlen = 1; m.msg_control = cbuf; m.msg_controllen = sizeof(cbuf);
    struct cmsghdr *c = CMSG_FIRSTHDR(&m);
    c->cmsg_level = SOL_SOCKET; c->cmsg_type = SCM_RIGHTS; c->cmsg_len = CMSG_LEN(sizeof(fds));
    memcpy(CMSG_DATA(c), fds, sizeof(fds));
    if (sendmsg(s, &m, 0) < 0) return 1;
    for (int i = 1; i < argc; i++) {
        int len = (int)strlen(argv[i]);
        if (full(s, &len, sizeof(len), 1) || full(s, argv[i], (size_t)len, 1)) return 1;
    }
    int code = 1;
    if (full(s, &code, sizeof(code), 0)) return 1;
    return code;
}

static void serve(int s) {
    int n = 0, fds[3] = {-1, -1, -1};
    char cbuf[CMSG_SPACE(sizeof(fds))];
    struct iovec io = {&n, sizeof(n)};
    struct msghdr m = {0};
    m.msg_iov = &io; m.msg_iovlen = 1; m.msg_control = cbuf; m.msg_controllen = sizeof(cbuf);
    if (recvmsg(s, &m, MSG_WAITALL) != sizeof(n)) _exit(1);
    struct cmsghdr *c = CMSG_FIRSTHDR(&m);
    if (!c || c->cmsg_type != SCM_RIGHTS) _exit(1);
    memcpy(fds, CMSG_DATA(c), sizeof(fds));
    if (n < 0 || n > 64) _exit(1);
    char *args[65] = {0};
    for (int i = 0; i < n; i++) {
        int len = 0;
        if (full(s, &len, sizeof(len), 0) || len < 0 || len > 65536) _exit(1);
        args[i] = calloc((size_t)len + 1, 1);
        if (full(s, args[i], (size_t)len, 0)) _exit(1);
    }
    int uid = 0; const char *cmd = NULL; int version = 0;
    for (int i = 0; i < n; i++) {
        const char *x = args[i];
        if (!strcmp(x, "-c") || !strcmp(x, "--command")) { if (i + 1 < n) cmd = args[++i]; }
        else if (!strcmp(x, "-v") || !strcmp(x, "--version") || !strcmp(x, "-V")) version = 1;
        else if (!strcmp(x, "shell")) uid = 2000;
        else if (!strcmp(x, "root")) uid = 0;
        else if (x[0] >= '0' && x[0] <= '9') uid = atoi(x);
        /* "-", "-l", "-mm", "--mount-master", "-p": accepted, nothing to do here */
    }
    int code = 1;
    if (version) { dprintf(fds[1], "avsu-ci\n"); code = 0; }
    else if (uid != 0 && getenv("AVSU_NO_UID")) { dprintf(fds[2], "su: switching to uid %d is not supported\n", uid); code = 1; }
    else {
        pid_t p = fork();
        if (p == 0) {
            setsid();
            dup2(fds[0], 0); dup2(fds[1], 1); dup2(fds[2], 2);
            for (int f = 3; f < 1024; f++) close(f);
            putenv(PATHV);
            if (uid != 0) {
                if (setgroups(0, NULL) || setresgid(uid, uid, uid) || setresuid(uid, uid, uid)) { perror("su: setuid"); _exit(1); }
            }
            signal(SIGCHLD, SIG_DFL); signal(SIGPIPE, SIG_DFL);
            if (cmd) execl(SH, "sh", "-c", cmd, (char *)NULL);
            else execl(SH, "sh", (char *)NULL);
            _exit(127);
        }
        int st = 0;
        while (waitpid(p, &st, 0) < 0 && errno == EINTR) {}
        code = WIFEXITED(st) ? WEXITSTATUS(st) : 128 + WTERMSIG(st);
    }
    full(s, &code, sizeof(code), 1);
    _exit(0);
}

static int daemon_main(void) {
    if (fork() > 0) return 0;
    setsid();
    if (fork() > 0) _exit(0);
    signal(SIGCHLD, SIG_IGN); signal(SIGPIPE, SIG_IGN);
    struct sockaddr_un a; socklen_t l = addr(&a);
    int s = socket(AF_UNIX, SOCK_STREAM, 0);
    if (s < 0 || bind(s, (struct sockaddr *)&a, l) < 0 || listen(s, 16) < 0) { perror("avsu: bind"); _exit(1); }
    for (;;) {
        int c = accept(s, NULL, NULL);
        if (c < 0) continue;
        if (fork() == 0) { close(s); signal(SIGCHLD, SIG_DFL); serve(c); }
        close(c);
    }
}

int main(int argc, char **argv) {
    if (argc > 1 && !strcmp(argv[1], "--daemon")) return daemon_main();
    return client(argc, argv);
}
