// network.cpp — реализация сетевых утилит (§1 архитектуры)
#include "network.hpp"
#include <cstdio>

#ifdef _WIN32
#include <winsock2.h>
#else
#include <sys/socket.h>
#include <netinet/in.h>
#include <arpa/inet.h>
#include <fcntl.h>
#include <unistd.h>
#endif

namespace vr::net {

#ifdef _WIN32
bool Init() {
    WSADATA d{};
    int rc = WSAStartup(MAKEWORD(2, 2), &d);
    if (rc != 0) {
        std::fprintf(stderr, "WSAStartup failed: %d\n", rc);
    }
    return rc == 0;
}
#else
bool Init() { return true; }
#endif

void Cleanup() {
#ifdef _WIN32
    WSACleanup();
#endif
}

void Close(SocketType socket) {
    if (socket == kInvalidSocket) return;
#ifdef _WIN32
    closesocket(socket);
#else
    close(socket);
#endif
}

bool SetTimeouts(SocketType socket, int milliseconds) {
#ifdef _WIN32
    const DWORD timeout = static_cast<DWORD>(milliseconds);
#else
    const timeval timeout{milliseconds / 1000, (milliseconds % 1000) * 1000};
#endif
    return setsockopt(socket, SOL_SOCKET, SO_RCVTIMEO, reinterpret_cast<const char*>(&timeout), sizeof(timeout)) == 0 &&
           setsockopt(socket, SOL_SOCKET, SO_SNDTIMEO, reinterpret_cast<const char*>(&timeout), sizeof(timeout)) == 0;
}

bool SendAll(SocketType socket, const char* data, size_t size) {
    while (size) {
#ifdef _WIN32
        const int sent = send(socket, data, static_cast<int>(size), 0);
#else
        const int sent = static_cast<int>(send(socket, data, size, MSG_NOSIGNAL));
#endif
        if (sent <= 0) return false;
        data += sent;
        size -= static_cast<size_t>(sent);
    }
    return true;
}

#ifdef _WIN32
bool SetUdpDoNotFragment(SocketType socket) {
    const BOOL enabled = TRUE;
    return setsockopt(socket, IPPROTO_IP, IP_DONTFRAGMENT,
                      reinterpret_cast<const char*>(&enabled), sizeof(enabled)) == 0;
}
#endif

bool SetNonBlocking(SocketType s, bool on) {
#ifdef _WIN32
    u_long arg = on ? 1 : 0;
    return ioctlsocket(s, FIONBIO, &arg) == 0;
#else
    int flags = fcntl(s, F_GETFL, 0);
    if (flags < 0) return false;
    return fcntl(s, F_SETFL, on ? (flags | O_NONBLOCK) : (flags & ~O_NONBLOCK)) != -1;
#endif
}

bool SetReuseAddr(SocketType s) {
    int opt = 1;
#ifdef _WIN32
    return setsockopt(s, SOL_SOCKET, SO_REUSEADDR,
                      reinterpret_cast<char*>(&opt), sizeof(opt)) == 0;
#else
    return setsockopt(s, SOL_SOCKET, SO_REUSEADDR, &opt, sizeof(opt)) == 0;
#endif
}

bool SetSendBufferSize(SocketType s, int bytes) {
    return setsockopt(s, SOL_SOCKET, SO_SNDBUF,
                      reinterpret_cast<char*>(&bytes), sizeof(bytes)) == 0;
}

bool SetRecvBufferSize(SocketType s, int bytes) {
    return setsockopt(s, SOL_SOCKET, SO_RCVBUF,
                      reinterpret_cast<char*>(&bytes), sizeof(bytes)) == 0;
}

bool BindAny(SocketType s, uint16_t port) {
    sockaddr_in sa{};
    sa.sin_family = AF_INET;
    sa.sin_addr.s_addr = INADDR_ANY;
    sa.sin_port = htons(port);
#ifdef _WIN32
    return bind(s, reinterpret_cast<sockaddr*>(&sa), sizeof(sa)) != SOCKET_ERROR;
#else
    return bind(s, reinterpret_cast<sockaddr*>(&sa), sizeof(sa)) == 0;
#endif
}

SocketType UdpSocket(bool reuseAddr) {
    SocketType s = socket(AF_INET, SOCK_DGRAM, 0);
    if (s == kInvalidSocket) return kInvalidSocket;
    if (reuseAddr) SetReuseAddr(s);
    return s;
}

SocketType TcpSocket(bool reuseAddr) {
    SocketType s = socket(AF_INET, SOCK_STREAM, 0);
    if (s == kInvalidSocket) return kInvalidSocket;
    if (reuseAddr) SetReuseAddr(s);
    return s;
}

}  // namespace vr::net
