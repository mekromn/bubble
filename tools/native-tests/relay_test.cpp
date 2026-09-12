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
    int disabledLogArgument = 0;
    BUBBLE_RELAY_LOG(ANDROID_LOG_INFO, "%d", ++disabledLogArgument);
    assert(disabledLogArgument == 0); // Compiled out, not a no-op function call.

    // A burst of 10,000 notifications requires a single eventfd increment.
    auto s=makeState();for(int i=0;i<10000;i++)s->wake();
    uint64_t n=0;assert(read(s->event.fd(),&n,sizeof(n))==sizeof(n));assert(n==1);
    // Use another state because reading outside beginPass deliberately doesn't clear its flag.
    s=makeState();
    // Four images: discard 1-3 with their own acquire fences; submit exactly #4.
    for(int i=1;i<=4;i++)enqueue(s,i,42);
    consume(s);assert(totalSubmitted==1);assert(backgroundClearWrites==1);assert(s->leases==1);assert(readable(s));
    assert(returnedImages==std::vector<int>({1,2,3}));assert(returnedFences==std::vector<int>({1001,1002,1003}));
    auto p=releaseOne(7004);assert(p.bufferId==4&&p.acquireFence==1004);assert(returnedFences.back()==7004);
    clear(s);consume(s);assert(!readable(s)); // Empty pass must not self-schedule.
    // One-image normal case is known empty after its second acquire: no redundant self-wake.
    enqueue(s,5,42);clear(s);int a=acquireCalls.load();consume(s);
    assert(acquireCalls==a+2);assert(!readable(s));assert(spaceWrites==1);releaseOne();assert(!readable(s));clear(s);
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
    assert(transactionCreates==txBefore);assert(backgroundClearWrites==1);assert(spaceWrites==spaceBefore);assert(opacityWrites==0);
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
    // A returned last frame is NOT a pending new frame. Exercise long idle
    // presentation/release sequences without adding production timing counters.
    s=makeState();
    const int beforeSerial=acquireCalls.load();
    for(int i=0;i<10000;i++) {
        enqueue(s,50000+i);consume(s);
        assert(!readable(s));releaseOne();assert(!readable(s));
    }
    assert(acquireCalls==beforeSerial+20000); // success + EMPTY, no release-only acquire
    assert(s->leases==0);s.reset();
    // MAX was observed, then a callback returned capacity before the consumer
    // can register a waiter. The epoch must turn the stale MAX into a retry.
    s=makeState();
    for(int i=0;i<6;i++){enqueue(s,60000+i);consume(s);clear(s);}
    enqueue(s,60006);
    afterMaxAcquire=[] { releaseOne(16000); };
    consume(s);assert(!afterMaxAcquire);assert(readable(s));
    clear(s);consume(s);assert(s->reader->queue.empty());assert(s->leases==6);
    // An extra producer notification at saturation may arm a waiter, but must
    // not turn into a self-scheduling busy loop while nothing is released.
    clear(s);imageAvailable(s.get(),s->reader);assert(readable(s));
    clear(s);consume(s);assert(!readable(s));
    while(!presented.empty()) { releaseOne(); }
    clear(s);assert(s->leases==0);s.reset();
    // If MAX is seen after acquiring the last available slot and that image
    // fails validation, its local return (not a SurfaceControl callback) is
    // enough to make pending reader work runnable. Preserve its acquire fence.
    s=makeState();
    for(int i=0;i<5;i++){enqueue(s,70000+i);consume(s);clear(s);}
    const auto errorsBefore=totalErrors.load();
    enqueue(s,70005);enqueue(s,70006);failHardwareBufferId=70005;
    consume(s);failHardwareBufferId=-1;
    assert(totalErrors==errorsBefore+1); // deliberate test-only API failure
    assert(returnedImages.back()==70005&&returnedFences.back()==71005);
    assert(readable(s));clear(s);consume(s);
    assert(s->reader->queue.empty());assert(s->leases==6);
    // Six release callbacks can overlap each other, not only acquisition.
    std::vector<std::thread> callbacks;
    for(int i=0;i<6;i++)callbacks.emplace_back([] { releaseOne(); });
    for(auto& t:callbacks) { t.join(); }
    clear(s);
    assert(s->leases==0);assert(totalOutstanding==0);assert(totalSubmitted==totalReleased);
    assert(totalErrors==errorsBefore+1);s.reset();
    // A full bounded pass may reject its final image with zero in-flight leases.
    // A queued fifth image then needs a backlog wake, not a release callback.
    s=makeState();
    for(int i=0;i<5;i++)enqueue(s,80000+i);
    failHardwareBufferId=80003;consume(s);failHardwareBufferId=-1;
    assert(s->leases==0&&s->reader->queue.size()==1);assert(readable(s));
    clear(s);consume(s);assert(s->reader->queue.empty()&&s->leases==1);
    releaseOne();assert(!readable(s));assert(totalErrors==errorsBefore+2);
    assert(totalOutstanding==0&&totalSubmitted==totalReleased);s.reset();
    std::cout << "PASS: rejected full-drain image cannot strand the next queued image without a callback.\n";
    // Preserve release-driven recovery when a transient acquire error follows
    // one successful acquire while another image is already queued.
    s=makeState();enqueue(s,90000);enqueue(s,90001);failAcquireCountdown=1;
    consume(s);assert(s->leases==1&&s->reader->queue.size()==1);
    assert(!readable(s));releaseOne();assert(readable(s));
    clear(s);consume(s);assert(s->leases==1&&s->reader->queue.empty());
    releaseOne();assert(!readable(s));assert(totalErrors==errorsBefore+3);
    assert(totalOutstanding==0&&totalSubmitted==totalReleased);s.reset();
    std::cout << "PASS: transient acquire failure retains release-driven recovery without error polling.\n";
    // Gate-level exhaustive order cases plus a concurrent arm/return race.
    bubble::RelayCapacity capacity;
    capacity.beginPass();auto epoch=capacity.beforeAcquire();
    assert(!capacity.waitAfterBlockedAcquire(epoch));assert(capacity.returned());assert(!capacity.returned());
    capacity.beginPass();epoch=capacity.beforeAcquire();
    assert(!capacity.returned());assert(capacity.waitAfterBlockedAcquire(epoch));
    capacity.beginPass();epoch=capacity.beforeAcquire();
    assert(!capacity.waitAfterBlockedAcquire(epoch));capacity.beginPass();assert(!capacity.returned());
    std::atomic<int> start{0},done{0};std::atomic<bool> callbackWake{false};
    std::thread racer([&] {
        for(int i=1;i<=20000;i++) {
            while(start.load()!=i)std::this_thread::yield();
            callbackWake.store(capacity.returned());done.store(i);
        }
    });
    for(int i=1;i<=20000;i++) {
        capacity.beginPass();epoch=capacity.beforeAcquire();start.store(i);
        const bool retry=capacity.waitAfterBlockedAcquire(epoch);
        while(done.load()!=i)std::this_thread::yield();
        assert(retry||callbackWake.load()); // one side must schedule progress
    }
    racer.join();
    std::cout << "PASS: 10,000 idle releases without consumer wakes; stale-MAX race; saturation without spin; local rejection recovery; six concurrent callbacks; 20,000 capacity arm/return races.\n";
    // Stress notify/beginPass interleaving with an external work sequence.
    bubble::RelayWake event;assert(event.open());std::atomic<int> offered{0};
    std::thread producer([&]{for(int i=1;i<=20000;i++){offered.store(i);assert(event.notify());if((i%7)==0)std::this_thread::yield();}});
    int seen=0;
    while(seen<20000){pollfd f{event.fd(),POLLIN,0};assert(poll(&f,1,5000)==1);assert(event.beginPass());seen=offered.load();}
    producer.join();
    std::cout<<"PASS: selected-policy drain, exact fences, six outstanding leases, capacity retry, empty-idle, colorspace reset, 10,000 allocation-free app submissions, cross-thread releases, 20,000 wake race iterations.\n";
}
