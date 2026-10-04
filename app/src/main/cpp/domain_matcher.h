#ifndef HAZE_DOMAIN_MATCHER_H_
#define HAZE_DOMAIN_MATCHER_H_

#include <string>
#include <unordered_set>
#include <vector>

namespace haze {

class DomainMatcher {
public:
    DomainMatcher() = default;
    ~DomainMatcher() = default;

    void AddDomain(const std::string& domain);
    void Clear();
    bool Matches(const std::string& host) const;
    size_t Size() const;

private:
    std::unordered_set<std::string> domains_;
};

}  // namespace haze

#endif  // HAZE_DOMAIN_MATCHER_H_
