#include "vea_s5_tensor.h"

#include <cstddef>
#include <limits>

namespace {
bool AddOverflow(size_t a, size_t b, size_t* out) {
    if (out == nullptr || b > std::numeric_limits<size_t>::max() - a) return true;
    *out = a + b;
    return false;
}
bool MultiplyOverflow(size_t a, size_t b, size_t* out) {
    if (out == nullptr || (a != 0u && b > std::numeric_limits<size_t>::max() / a)) return true;
    *out = a * b;
    return false;
}
}  // namespace

extern "C" VeaS5TensorStatus vea_s5_gemm_row_major_f32(
    const float* a, const float* b, float* c,
    size_t m, size_t n, size_t k,
    float alpha, size_t lda, size_t ldb, float beta, size_t ldc) {
    if (m == 0u || n == 0u || lda < k || ldb < n || ldc < n || c == nullptr ||
        (k != 0u && (a == nullptr || b == nullptr))) {
        return VEA_S5_TENSOR_STATUS_INVALID_ARGUMENT;
    }

    size_t offset = 0u, extent = 0u;
    if (MultiplyOverflow(m - 1u, lda, &offset) ||
        (k != 0u && (AddOverflow(offset, k, &extent))) ||
        MultiplyOverflow(k == 0u ? 0u : k - 1u, ldb, &offset) ||
        (k != 0u && AddOverflow(offset, n, &extent)) ||
        MultiplyOverflow(m - 1u, ldc, &offset) || AddOverflow(offset, n, &extent)) {
        return VEA_S5_TENSOR_STATUS_SIZE_OVERFLOW;
    }

    for (size_t row = 0; row < m; ++row) {
        const float* a_row = k == 0u ? nullptr : a + row * lda;
        float* c_row = c + row * ldc;
        for (size_t col = 0; col < n; ++col) {
            float sum = 0.0f;
            for (size_t inner = 0; inner < k; ++inner) {
                // K advances monotonically; source code unrolls this loop by four.
                sum += a_row[inner] * b[inner * ldb + col];
            }
            c_row[col] = beta * c_row[col] + sum * alpha;
        }
    }
    return VEA_S5_TENSOR_STATUS_OK;
}
