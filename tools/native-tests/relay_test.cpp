// Executes the actual relay against deterministic fake Android APIs. This is a
// lifetime/progress test, NOT a GPU/Android/Pixel latency benchmark.
#include "../../app/src/main/cpp/bubble_ahb.cpp"
#include <chrono>
#include <iostream>
using namespace std::chrono_literals;
void enqueue(const std::shared_ptr<State>& s,int id,int space=0) {
    std::lock_guard<std::mutex> lock(s->reader->mutex);
    s->reader->queue.push_back(new AImage{s->reader,{id},1000+id,space});
}
bool readable(const std::shared_ptr<State>& s) { pollfd f{s->event.fd(),POLLIN,0};return poll(&f,1,0)==1; }
void clear(const std::shared_ptr<State>& s) {if(readable(s))assert(s->event.beginPass());}
Pending releaseOne(int fence=9000) {
    Pending p;
    {std::lock_guard<std::mutex> lock(presentedMutex);assert(!presented.empty());p=presented.front();presented.pop_front();}
    p.callback(p.context,fence);return p;
}
std::shared_ptr<State> makeState() {
    auto s=std::make_shared<State>();assert(s->event.open());
    assert(AImageReader_newWithUsage(10,10,AIMAGE_FORMAT_PRIVATE,kConsumerUsage,kMaxImages,&s->reader)==0);
    AImageReader_getWindow(s->reader,&s->producer);s->output=new ASurfaceControl;
    s->frameTransaction=ASurfaceTransaction_create();return s;
}
int main() {
    // A burst of 10,000 notifications requires a single eventfd increment.
    auto s=makeState();for(int i=0;i<10000;i++)s->wake();
    uint64_t n=0;assert(read(s->event.fd(),&n,sizeof(n))==sizeof(n));assert(n==1);
    // Use another state because reading outside beginPass deliberately doesn't clear its flag.
    s=makeState();
    // Four images: discard 1-3 with their own acquire fences; submit exactly #4.
    for(int i=1;i<=4;i++)enqueue(s,i,42);
    consume(s);assert(totalSubmitted==1);assert(s->leases==1);assert(readable(s));
    assert(returnedImages==std::vector<int>({1,2,3}));assert(returnedFences==std::vector<int>({1001,1002,1003}));
    auto p=releaseOne(7004);assert(p.bufferId==4&&p.acquireFence==1004);assert(returnedFences.back()==7004);
    clear(s);consume(s);assert(!readable(s)); // Empty pass must not self-schedule.
    // One-image normal case is known empty after its second acquire: no redundant self-wake.
    enqueue(s,5,42);clear(s);int a=acquireCalls.load();consume(s);
    assert(acquireCalls==a+2);assert(!readable(s));assert(spaceWrites==1);releaseOne();clear(s);
    // Colorspace must be reset when next frame returns to UNKNOWN.
    enqueue(s,6,0);consume(s);assert(spaceWrites==2);releaseOne();clear(s);
    // Six outstanding images use exactly six stable callback slots; no seventh acquisition.
    for(int i=10;i<16;i++){enqueue(s,i);consume(s);clear(s);}
    assert(s->leases==6);enqueue(s,16);consume(s);assert(!readable(s));
    releaseOne();assert(readable(s));clear(s);consume(s);assert(s->leases==6);
    while(!presented.empty()) { releaseOne(); }
    clear(s);assert(s->leases==0);
    // 10,000 serial frames must reuse the same transaction and pool slots.
    const int txBefore=transactionCreates.load(), spaceBefore=spaceWrites.load();
    for(int i=0;i<10000;i++){enqueue(s,100+i);consume(s);releaseOne();clear(s);}
    assert(transactionCreates==txBefore);assert(spaceWrites==spaceBefore);assert(opacityWrites==0);
    assert(totalOutstanding==0);assert(totalSubmitted==totalReleased);assert(totalErrors==0);
    // Release can be on another thread; recycle bookkeeping only after copying ownership.
    for(int i=0;i<6;i++){enqueue(s,20000+i);consume(s);clear(s);}
    std::thread callbackThread([]{for(int i=0;i<6;i++)releaseOne();});callbackThread.join();
    assert(s->leases==0);for(auto& l:s->leaseSlots)assert(!l.occupied.load()&&!l.state&&l.image==nullptr);
    // Overlap acquisition/submission and callbacks, exercising slot reuse while
    // earlier callbacks are still returning capacity. This is not Android timing.
    for(int i=0;i<10000;i++) enqueue(s,30000+i);
    std::atomic<bool> producerDone{false};
    std::thread simultaneousReleases([&]{
        for(;;) {
            Pending pending{}; bool has=false;
            { std::lock_guard<std::mutex> lock(presentedMutex);
              if(!presented.empty()){pending=presented.front();presented.pop_front();has=true;}
              else if(producerDone.load()) break;
            }
            if(has) pending.callback(pending.context,9999);
            else std::this_thread::yield();
        }
    });
    s->wake();
    for(;;){
        pollfd fd{s->event.fd(),POLLIN,0};assert(poll(&fd,1,5000)==1);
        assert(s->event.beginPass());consume(s);
        { std::lock_guard<std::mutex> lock(s->reader->mutex);if(s->reader->queue.empty())break; }
    }
    producerDone.store(true);simultaneousReleases.join();
    assert(s->leases==0);assert(totalOutstanding==0);assert(totalSubmitted==totalReleased);assert(totalErrors==0);
    for(auto& l:s->leaseSlots)assert(!l.occupied.load()&&!l.state&&l.image==nullptr);
    std::cout << "PASS: 10,000 additional images with overlapping consume/release callback and slot reuse.\n";
    s.reset();
    // Stress notify/beginPass interleaving with an external work sequence.
    bubble::RelayWake event;assert(event.open());std::atomic<int> offered{0};
    std::thread producer([&]{for(int i=1;i<=20000;i++){offered.store(i);assert(event.notify());if((i%7)==0)std::this_thread::yield();}});
    int seen=0;
    while(seen<20000){pollfd f{event.fd(),POLLIN,0};assert(poll(&f,1,5000)==1);assert(event.beginPass());seen=offered.load();}
    producer.join();
    std::cout<<"PASS: selected-policy drain, exact fences, six outstanding leases, capacity retry, empty-idle, colorspace reset, 10,000 allocation-free app submissions, cross-thread releases, 20,000 wake race iterations.\n";
}
