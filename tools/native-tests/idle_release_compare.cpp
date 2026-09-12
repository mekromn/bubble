// Deterministic operation-count comparison, NOT a frame-rate/latency benchmark.
// Compile this unchanged harness against both real 137 and 138 relay sources.
#include "../../app/src/main/cpp/bubble_ahb.cpp"
#include <iostream>
int main() {
    auto s=std::make_shared<State>();assert(s->event.open());
    assert(AImageReader_newWithUsage(10,10,AIMAGE_FORMAT_PRIVATE,kConsumerUsage,kMaxImages,&s->reader)==0);
    AImageReader_getWindow(s->reader,&s->producer);s->output=new ASurfaceControl;
    s->frameTransaction=ASurfaceTransaction_create();
    int releaseOnlyPasses=0;
    constexpr int frames=10000;
    for(int i=0;i<frames;i++) {
        {
            std::lock_guard<std::mutex> lock(s->reader->mutex);
            s->reader->queue.push_back(new AImage{s->reader,{i},1000+i,0});
        }
        // The normal frame callback has woken the worker; run its consumer pass.
        consume(s);
        assert(presented.size()==1);
        auto p=presented.front();presented.pop_front();p.callback(p.context,2000+i);
        pollfd fd{s->event.fd(),POLLIN,0};
        if(poll(&fd,1,0)==1) {
            releaseOnlyPasses++;assert(s->event.beginPass());consume(s);
        }
        assert(s->leases==0&&presented.empty());
        assert(poll(&fd,1,0)==0); // No repeat loop with an empty reader.
    }
    assert(totalErrors==0&&totalOutstanding==0);
    assert(totalSubmitted==frames&&totalReleased==frames);
    std::cout << "{\"sequence\":\"one image, empty queue, release; repeated\","
              << "\"frames\":" << frames
              << ",\"submissions\":" << totalSubmitted.load()
              << ",\"releases\":" << totalReleased.load()
              << ",\"acquireCalls\":" << acquireCalls.load()
              << ",\"releaseOnlyConsumerPasses\":" << releaseOnlyPasses
              << ",\"frameTransactionsCreated\":" << transactionCreates.load()
              << ",\"errors\":" << totalErrors.load()
              << ",\"performanceTimingMeasured\":false}\n";
    s.reset();
}
