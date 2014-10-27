# Chadash
An immutable cloud deployer for Amazon.

#### What's in the name?
Chadash (khaw-dawsh') comes from the Hebrew word: **חָדָש**

In the Strongs Greek and Hebrew dictionary, the definition for this word (Strongs #H2319) is:
> fresh, new thing

#### Why didn't you just use Netflix's Asgard?

Asgard is a great product, but we differ in the fundamentals of how to deploy immutable software on AWS. Asgard mutates
the existing ELB, and for a short while, you have both the old instances and the new instances receiving traffic. While
this may work for your situation, in most cases it will not as customers can thrash between two versions for a
short time.

Chadash takes a completely different approach. Immutability goes beyond just the way we create instances, it goes all the way up to
the DNS level. Every new deploy gets a fresh new ELB. Using Route 53, we update the DNS to point at the new ELB.
Because your TTL is set to 60 seconds on Route 53, all clients should resolve to the new ELB within 1 minute of a change.
Once they resolve, they won't go back to any old instances. This was a problem with Asgard - an ELB could evenly
distribute the traffic across both versions. A customer may get one result the first hit, and on his next hit, he gets
another result.

Immutability was the cause for inspiration to build this product. We love immutable code, immutable servers, and now,
immutable AWS services.

Go forth and build fresh, new things!
