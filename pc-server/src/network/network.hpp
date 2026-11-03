// network.hpp — обёртки над Winsock2 для UDP/TCP
// Портативность: при отсутствии _WIN32 подставляется POSIX-заглушки.
// В production-версии здесь будут классы UDPSocket / TCPServer / TCPConnection.

#pragma once
#include <cstddef>
#include <cstdint>

#ifdef _WIN32
#include <winsock2.h>
#include <ws2tcpip.h>
#pragma comment(lib, "ws2_32.lib")
#else
#include <sys/socket.h>
#include <netinet/in.h>
#include <unistd.h>
#endif

namespace vr::net {

#ifdef _WIN32
using SocketType = SOCKET;
using AddressLength = int;
constexpr SocketType kInvalidSocket = INVALID_SOCKET;
#else
using SocketType = int;
using AddressLength = socklen_t;
constexpr SocketType kInvalidSocket = -1;
#endif

// Инициализация Winsock (noop на POSIX)
bool Init();
void Cleanup();
void Close(SocketType socket);
bool SetTimeouts(SocketType socket, int milliseconds);
bool SendAll(SocketType socket, const char* data, size_t size);

// Утилиты сокетов
bool SetNonBlocking(SocketType s, bool on);
bool SetReuseAddr(SocketType s);
bool SetSendBufferSize(SocketType s, int bytes);
bool SetRecvBufferSize(SocketType s, int bytes);
#ifdef _WIN32
// IP_DONTFRAG: запрещает фрагментацию на уровне IP (помогает избежать
// накладных расходов заголовков IP-фрагментов на Wi-Fi).
bool SetUdpDoNotFragment(SocketType s);
#else
inline bool SetUdpDoNotFragment(SocketType) { return true; }
#endif

// Bind к порту: AF_INET, INADDR_ANY.
bool BindAny(SocketType s, uint16_t port);

// Socket factory
SocketType UdpSocket(bool reuseAddr = true);
SocketType TcpSocket(bool reuseAddr = true);

}  // namespace vr::net
