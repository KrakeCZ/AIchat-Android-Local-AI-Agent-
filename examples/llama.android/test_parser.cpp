#include <iostream>
#include <fstream>
#include <vector>
#include <string>
#include <cstring>
#include <unordered_map>

struct TensorRef {
    size_t offset = 0;
    size_t size = 0;
};

int main() {
    std::ifstream f("vae_head.bin", std::ios::binary);
    if (!f.is_open()) {
        std::cout << "Could not open vae_head.bin\n";
        return 1;
    }
    std::vector<char> data((std::istreambuf_iterator<char>(f)), std::istreambuf_iterator<char>());
    uint64_t headerLen = 0;
    std::memcpy(&headerLen, data.data(), 8);
    std::cout << "HeaderLen: " << headerLen << "\n";
    const char* headerStr = data.data() + 8;
    
    std::unordered_map<std::string, TensorRef> tensors;
    size_t pos = 0;
    while (pos < headerLen) {
        const char* quote1 = strchr(headerStr + pos, '"');
        if (!quote1 || quote1 >= headerStr + headerLen) break;
        const char* quote2 = strchr(quote1 + 1, '"');
        if (!quote2 || quote2 >= headerStr + headerLen) break;

        std::string key(quote1 + 1, quote2 - quote1 - 1);
        pos = (quote2 + 1) - headerStr;

        if (key == "__metadata__") {
            continue;
        }

        // Find data_offsets for this key
        const char* blockEnd = strchr(quote2, '}');
        if (!blockEnd || blockEnd >= headerStr + headerLen) break;

        const char* offsetsPos = strstr(quote2, "\"data_offsets\"");
        if (offsetsPos && offsetsPos < blockEnd) {
            const char* bracketOpen = strchr(offsetsPos, '[');
            if (bracketOpen && bracketOpen < blockEnd) {
                size_t startOffset = static_cast<size_t>(std::strtoull(bracketOpen + 1, nullptr, 10));
                const char* comma = strchr(bracketOpen, ',');
                if (comma && comma < blockEnd) {
                    size_t endOffset = static_cast<size_t>(std::strtoull(comma + 1, nullptr, 10));
                    TensorRef ref;
                    ref.offset = startOffset;
                    ref.size = (endOffset > startOffset) ? (endOffset - startOffset) : 0;
                    tensors[key] = ref;
                }
            }
        }
        pos = (blockEnd + 1) - headerStr;
    }
    
    std::cout << "Parsed tensors count: " << tensors.size() << "\n";
    for (auto& k : {"decoder.conv1.weight", "decoder.head.0.gamma", "decoder.head.2.bias", "decoder.head.2.weight"}) {
        if (tensors.count(k)) {
            std::cout << "Found " << k << ": offset=" << tensors[k].offset << ", size=" << tensors[k].size << "\n";
        } else {
            std::cout << "MISSING " << k << "!\n";
        }
    }
    return 0;
}
