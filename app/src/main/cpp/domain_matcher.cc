#include "domain_matcher.h"
#include <algorithm>
#include <cctype>

namespace haze {

namespace {
std::string NormalizeHost(const std::string& input) {
    std::string clean = input;
    std::transform(clean.begin(), clean.end(), clean.begin(), [](unsigned char c) {
        return std::tolower(c);
    });
    if (clean.rfind("www.", 0) == 0) {
        clean = clean.substr(4);
    }
    return clean;
}
}  // namespace

void DomainMatcher::AddDomain(const std::string& domain) {
    if (domain.empty()) return;
    domains_.insert(NormalizeHost(domain));
}

void DomainMatcher::Clear() {
    domains_.clear();
}

size_t DomainMatcher::Size() const {
    return domains_.size();
}

bool DomainMatcher::Matches(const std::string& host) const {
    if (host.empty() || domains_.empty()) return false;
    std::string current = NormalizeHost(host);

    if (domains_.count(current) > 0) {
        return true;
    }

    // Check parent domain suffixes (e.g. sub.ad.doubleclick.net -> doubleclick.net)
    size_t dot_pos = current.find('.');
    while (dot_pos != std::string::npos) {
        current = current.substr(dot_pos + 1);
        if (domains_.count(current) > 0) {
            return true;
        }
        dot_pos = current.find('.');
    }

    return false;
}

}  // namespace haze
