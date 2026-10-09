#include "vec.h"
#include <cassert>
#include <cfenv>
#include <chrono>
#include <cstdint>
#include <cstring>
#include <iostream>
#include <cstdlib>
#include <random>
#include <vector>
#include <sys/mman.h>
#include <unistd.h>
#ifndef CANDIDATE_NAME
#define CANDIDATE_NAME "portable_loop_unroll"
#endif
extern "C" {

float ggml_table_f32_f16[1 << 16];
#ifndef __ANDROID__
[[noreturn]] void __assert2(const char *file, int line, const char *fn, const char *expr) {
    std::cerr << "Android kernel assertion " << file << ":" << line << " " << fn << " " << expr << "\n";
    std::abort();
}
#endif
void dot_old(int, float *, size_t, ggml_fp16_t *, size_t, ggml_fp16_t *, size_t, int);
void dot_new(int, float *, size_t, ggml_fp16_t *, size_t, ggml_fp16_t *, size_t, int);
void load_old(const ggml_fp16_t *x, float *out) { _mm_storeu_ps(out,__sse_f16x4_load(x)); }
void load_new(const ggml_fp16_t *x, float *out) { _mm_storeu_ps(out,__sse_f16x4_load(x)); }
}
static uint32_t bits(float f) { uint32_t value; std::memcpy(&value, &f, 4); return value; }
int main() {
    for (unsigned h=0; h<65536; ++h) ggml_table_f32_f16[h] = GGML_COMPUTE_FP16_TO_FP32(static_cast<ggml_fp16_t>(h));
    int mismatches=0;

    for (int rounding : {FE_TONEAREST, FE_DOWNWARD, FE_UPWARD, FE_TOWARDZERO}) {
        assert(std::fesetround(rounding)==0 && std::fegetround()==rounding);
        for (unsigned h=0; h<65536; h+=4) {
            ggml_fp16_t input[4]; float a[4], b[4];
            for (int j=0;j<4;++j) input[j]=h+j;
            load_old(input,a); load_new(input,b);
            for (int j=0;j<4;++j) if (bits(a[j])!=bits(b[j])) {
                if (mismatches<4) std::cout<<"half_mismatch="<<(h+j)<<" old="<<bits(a[j])<<" new="<<bits(b[j])<<"\n";
                ++mismatches;
            }
        }
    }
    assert(std::fesetround(FE_TONEAREST)==0);
    auto page=static_cast<size_t>(sysconf(_SC_PAGESIZE));
    auto *memory=static_cast<unsigned char *>(mmap(nullptr,page*2,PROT_READ|PROT_WRITE,MAP_PRIVATE|MAP_ANONYMOUS,-1,0));
    assert(memory!=MAP_FAILED && mprotect(memory+page,page,PROT_NONE)==0);
    auto *edge=reinterpret_cast<ggml_fp16_t *>(memory+page-8); float a[4],b[4];
    for(int j=0;j<4;++j) edge[j]=0x3c00+j;
    load_old(edge,a);load_new(edge,b);
    for(int j=0;j<4;++j) assert(bits(a[j])==bits(b[j]));
    assert(munmap(memory,page*2)==0);
    for(int n : {1,3,4,31,32,33}) {
        auto *x_memory=static_cast<unsigned char *>(mmap(nullptr,page*2,PROT_READ|PROT_WRITE,MAP_PRIVATE|MAP_ANONYMOUS,-1,0));
        auto *y_memory=static_cast<unsigned char *>(mmap(nullptr,page*2,PROT_READ|PROT_WRITE,MAP_PRIVATE|MAP_ANONYMOUS,-1,0));
        assert(x_memory!=MAP_FAILED && y_memory!=MAP_FAILED);
        assert(mprotect(x_memory+page,page,PROT_NONE)==0 && mprotect(y_memory+page,page,PROT_NONE)==0);
        auto *x=reinterpret_cast<ggml_fp16_t *>(x_memory+page-n*sizeof(ggml_fp16_t));
        auto *y=reinterpret_cast<ggml_fp16_t *>(y_memory+page-n*sizeof(ggml_fp16_t));
        for(int i=0;i<n;++i) { x[i]=0x3c00+i; y[i]=0xbc00+i; }
        float old_value,new_value;
        dot_old(n,&old_value,0,x,0,y,0,1);dot_new(n,&new_value,0,x,0,y,0,1);
        assert(bits(old_value)==bits(new_value));
        assert(munmap(x_memory,page*2)==0 && munmap(y_memory,page*2)==0);
    }
    std::mt19937 random(9170);
    int comparisons=0;
    for(int rounding : {FE_TONEAREST, FE_DOWNWARD, FE_UPWARD, FE_TOWARDZERO}) {
      assert(std::fesetround(rounding)==0 && std::fegetround()==rounding);
      for(int n : {1,3,4,7,31,32,33,64,128,383,384,385,1152,4096}) for(int repeat=0;repeat<128;++repeat) {
        std::vector<ggml_fp16_t> x(n),y(n);
        for(int i=0;i<n;++i) { x[i]=static_cast<ggml_fp16_t>(random()%0x7c00 | ((random()&1)<<15)); y[i]=static_cast<ggml_fp16_t>(random()%0x7c00 | ((random()&1)<<15)); }
        float old_value,new_value; dot_old(n,&old_value,0,x.data(),0,y.data(),0,1);dot_new(n,&new_value,0,x.data(),0,y.data(),0,1);
        if(bits(old_value)!=bits(new_value)) ++mismatches;
        ++comparisons;
      }
    }
    assert(std::fesetround(FE_TONEAREST)==0);
    std::cout<<"half_patterns=65536 rounding_modes=4 dot_comparisons="<<comparisons<<" conversion_protected_page_edge=PASS dot_protected_page_lengths=6 bit_mismatches="<<mismatches<<"\n";
    if(mismatches) return 1;
    const int n=384,rows=768,iterations=1200;
    std::vector<ggml_fp16_t> weights(n*rows),activations(n);
    for(auto &v:weights) v=static_cast<ggml_fp16_t>(0x1000+(random()%0x3800) | ((random()&1)<<15));
    for(auto &v:activations) v=static_cast<ggml_fp16_t>(0x1800+(random()%0x3800) | ((random()&1)<<15));
    volatile float sink=0;
    for(int run=0;run<4;++run) for(int kind : (run&1 ? std::vector<int>{1,0}:std::vector<int>{0,1})) {
        auto started=std::chrono::steady_clock::now();
        auto fn=kind==0?dot_old:dot_new;
        for(int i=0;i<iterations;++i) for(int r=0;r<rows;++r) { float value;fn(n,&value,0,weights.data()+r*n,0,activations.data(),0,1);sink=value; }
        auto elapsed=std::chrono::duration<double,std::milli>(std::chrono::steady_clock::now()-started).count();
        std::cout<<"run="<<run<<" kernel="<<(kind==0?"baseline":CANDIDATE_NAME)<<" matrix_ms="<<elapsed<<" sink="<<sink<<"\n";
    }
}
